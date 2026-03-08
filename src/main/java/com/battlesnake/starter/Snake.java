package com.battlesnake.starter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.util.*;
import java.util.stream.Collectors;

import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.get;

/**
 * Battlesnake server: pathfinding to food first, exit-route safety for body
 * "boxes", length- and position-aware decisions. No random moves.
 */
public class Snake {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final Handler HANDLER = new Handler();
    private static final Logger LOG = LoggerFactory.getLogger(Snake.class);

    private static final String[] DIRECTIONS = {"up", "down", "left", "right"};

    public static void main(String[] args) {
        String port = System.getProperty("PORT");
        if (port == null) {
            LOG.info("Using default port: {}", port);
            port = "8080";
        } else {
            LOG.info("Found system provided port: {}", port);
        }
        port(Integer.parseInt(port));
        get("/", HANDLER::process, JSON_MAPPER::writeValueAsString);
        post("/start", HANDLER::process, JSON_MAPPER::writeValueAsString);
        post("/move", HANDLER::process, JSON_MAPPER::writeValueAsString);
        post("/end", HANDLER::process, JSON_MAPPER::writeValueAsString);
    }

    public static class Handler {

        private static final Map<String, String> EMPTY = new HashMap<>();

        public Map<String, String> process(Request req, Response res) {
            try {
                JsonNode parsedRequest = JSON_MAPPER.readTree(req.body());
                String uri = req.uri();
                LOG.info("{} called with: {}", uri, req.body());
                Map<String, String> snakeResponse;
                if (uri.equals("/")) {
                    snakeResponse = index();
                } else if (uri.equals("/start")) {
                    snakeResponse = start(parsedRequest);
                } else if (uri.equals("/move")) {
                    snakeResponse = move(parsedRequest);
                } else if (uri.equals("/end")) {
                    snakeResponse = end(parsedRequest);
                } else {
                    throw new IllegalAccessError("Strange call made to the snake: " + uri);
                }

                LOG.info("Responding with: {}", JSON_MAPPER.writeValueAsString(snakeResponse));

                return snakeResponse;
            } catch (JsonProcessingException e) {
                LOG.warn("Something went wrong!", e);
                return null;
            }
        }

        public Map<String, String> index() {
            Map<String, String> response = new HashMap<>();
            response.put("apiversion", "1");
            response.put("author", "");
            response.put("color", "#888888");
            response.put("head", "default");
            response.put("tail", "default");
            return response;
        }

        public Map<String, String> start(JsonNode startRequest) {
            LOG.info("START");
            return EMPTY;
        }

        public Map<String, String> move(JsonNode moveRequest) {
            try {
                LOG.info("Data: {}", JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(moveRequest));
            } catch (JsonProcessingException e) {
                LOG.error("Error parsing payload", e);
            }

            JsonNode boardNode = moveRequest.get("board");
            JsonNode you = moveRequest.get("you");
            int width = boardNode.get("width").asInt();
            int height = boardNode.get("height").asInt();

            int headX = you.get("head").get("x").asInt();
            int headY = you.get("head").get("y").asInt();
            int health = you.get("health").asInt();
            JsonNode bodyNode = you.get("body");
            List<int[]> myBody = new ArrayList<>();
            for (JsonNode seg : bodyNode) {
                myBody.add(new int[]{seg.get("x").asInt(), seg.get("y").asInt()});
            }
            int length = myBody.size();

            List<int[]> food = new ArrayList<>();
            for (JsonNode f : boardNode.get("food")) {
                food.add(new int[]{f.get("x").asInt(), f.get("y").asInt()});
            }

            Set<Long> otherBodies = new HashSet<>();
            String myId = you.get("id").asText();
            for (JsonNode snake : boardNode.get("snakes")) {
                if (myId.equals(snake.get("id").asText())) continue;
                for (JsonNode seg : snake.get("body")) {
                    otherBodies.add(toKey(seg.get("x").asInt(), seg.get("y").asInt()));
                }
            }

            Set<Long> hazards = new HashSet<>();
            if (boardNode.has("hazards")) {
                for (JsonNode h : boardNode.get("hazards")) {
                    hazards.add(toKey(h.get("x").asInt(), h.get("y").asInt()));
                }
            }

            List<String> safeMoves = getSafeMoves(headX, headY, myBody, otherBodies, hazards, width, height);
            if (safeMoves.isEmpty()) {
                Map<String, String> response = new HashMap<>();
                response.put("move", "up");
                return response;
            }

            Set<Long> foodSet = new HashSet<>();
            for (int[] f : food) foodSet.add(toKey(f[0], f[1]));

            List<String> withExit = safeMoves.stream()
                    .filter(m -> hasExitRoute(m, headX, headY, myBody, otherBodies, hazards, foodSet, width, height))
                    .collect(Collectors.toList());

            List<String> candidates = withExit.isEmpty() ? safeMoves : withExit;

            String moveToFood = pathToNearestFood(headX, headY, myBody, otherBodies, hazards, food, width, height);
            boolean preferFood = health <= 50 || length <= 3 || (food.size() > 0 && moveToFood != null);

            String chosen;
            if (preferFood && moveToFood != null && candidates.contains(moveToFood)) {
                chosen = moveToFood;
            } else {
                chosen = bestSafeMove(candidates, headX, headY, myBody, otherBodies, hazards, width, height, length);
            }

            LOG.info("MOVE {}", chosen);
            Map<String, String> response = new HashMap<>();
            response.put("move", chosen);
            return response;
        }

        private List<String> getSafeMoves(int hx, int hy, List<int[]> myBody, Set<Long> otherBodies,
                                         Set<Long> hazards, int width, int height) {
            Set<Long> myBodySet = new HashSet<>();
            for (int[] p : myBody) myBodySet.add(toKey(p[0], p[1]));
            int tailX = myBody.get(myBody.size() - 1)[0];
            int tailY = myBody.get(myBody.size() - 1)[1];
            long tailKey = toKey(tailX, tailY);

            List<String> out = new ArrayList<>();
            for (String dir : DIRECTIONS) {
                int nx = hx, ny = hy;
                switch (dir) {
                    case "up":    ny = hy + 1; break;
                    case "down":  ny = hy - 1; break;
                    case "left":  nx = hx - 1; break;
                    case "right": nx = hx + 1; break;
                }
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                long key = toKey(nx, ny);
                if (otherBodies.contains(key) || hazards.contains(key)) continue;
                if (myBodySet.contains(key) && key != tailKey) continue;
                if (myBody.size() >= 2) {
                    int neckX = myBody.get(1)[0], neckY = myBody.get(1)[1];
                    if (nx == neckX && ny == neckY) continue;
                }
                out.add(dir);
            }
            return out;
        }

        private boolean hasExitRoute(String move, int hx, int hy, List<int[]> myBody, Set<Long> otherBodies,
                                    Set<Long> hazards, Set<Long> foodSet, int width, int height) {
            int nx = hx, ny = hy;
            switch (move) {
                case "up":    ny = hy + 1; break;
                case "down":  ny = hy - 1; break;
                case "left":  nx = hx - 1; break;
                case "right": nx = hx + 1; break;
            }
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) return false;

            boolean eating = foodSet.contains(toKey(nx, ny));
            List<int[]> bodyAfter = new ArrayList<>();
            bodyAfter.add(new int[]{nx, ny});
            for (int i = 0; i < (eating ? myBody.size() : myBody.size() - 1); i++) {
                bodyAfter.add(myBody.get(i));
            }
            int tailX = bodyAfter.get(bodyAfter.size() - 1)[0];
            int tailY = bodyAfter.get(bodyAfter.size() - 1)[1];

            Set<Long> blocked = new HashSet<>(otherBodies);
            blocked.addAll(hazards);
            for (int i = 0; i < bodyAfter.size() - 1; i++) {
                int[] p = bodyAfter.get(i);
                blocked.add(toKey(p[0], p[1]));
            }

            int dist = bfsDist(nx, ny, tailX, tailY, blocked, width, height);
            return dist >= 0;
        }

        private int bfsDist(int sx, int sy, int tx, int ty, Set<Long> blocked, int width, int height) {
            if (sx == tx && sy == ty) return 0;
            Queue<int[]> q = new ArrayDeque<>();
            Set<Long> seen = new HashSet<>();
            q.add(new int[]{sx, sy});
            seen.add(toKey(sx, sy));
            int[] dx = {0, 0, -1, 1};
            int[] dy = {1, -1, 0, 0};
            int dist = 0;
            while (!q.isEmpty()) {
                int level = q.size();
                for (int i = 0; i < level; i++) {
                    int[] cur = q.poll();
                    int cx = cur[0], cy = cur[1];
                    if (cx == tx && cy == ty) return dist;
                    for (int d = 0; d < 4; d++) {
                        int nx = cx + dx[d], ny = cy + dy[d];
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                        long key = toKey(nx, ny);
                        if (blocked.contains(key) || seen.contains(key)) continue;
                        seen.add(key);
                        q.add(new int[]{nx, ny});
                    }
                }
                dist++;
            }
            return -1;
        }

        private String pathToNearestFood(int hx, int hy, List<int[]> myBody, Set<Long> otherBodies,
                                        Set<Long> hazards, List<int[]> food, int width, int height) {
            Set<Long> blocked = new HashSet<>(otherBodies);
            for (int[] p : myBody) blocked.add(toKey(p[0], p[1]));
            blocked.addAll(hazards);

            int bestDist = Integer.MAX_VALUE;
            int[] bestFirst = null;
            for (int[] f : food) {
                List<int[]> path = bfsPath(hx, hy, f[0], f[1], blocked, width, height);
                if (path != null && path.size() >= 2 && path.size() < bestDist) {
                    bestDist = path.size();
                    bestFirst = path.get(1);
                }
            }
            if (bestFirst == null) return null;
            int fx = bestFirst[0], fy = bestFirst[1];
            if (fx == hx && fy == hy + 1) return "up";
            if (fx == hx && fy == hy - 1) return "down";
            if (fx == hx - 1 && fy == hy) return "left";
            if (fx == hx + 1 && fy == hy) return "right";
            return null;
        }

        private List<int[]> bfsPath(int sx, int sy, int tx, int ty, Set<Long> blocked, int width, int height) {
            Queue<int[]> q = new ArrayDeque<>();
            Map<Long, int[]> parent = new HashMap<>();
            q.add(new int[]{sx, sy});
            parent.put(toKey(sx, sy), null);
            int[] dx = {0, 0, -1, 1};
            int[] dy = {1, -1, 0, 0};
            while (!q.isEmpty()) {
                int[] cur = q.poll();
                int cx = cur[0], cy = cur[1];
                if (cx == tx && cy == ty) {
                    List<int[]> path = new ArrayList<>();
                    int[] p = cur;
                    while (p != null) {
                        path.add(0, p);
                        p = parent.get(toKey(p[0], p[1]));
                    }
                    return path;
                }
                for (int d = 0; d < 4; d++) {
                    int nx = cx + dx[d], ny = cy + dy[d];
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    long key = toKey(nx, ny);
                    if (blocked.contains(key) || parent.containsKey(key)) continue;
                    parent.put(key, cur);
                    q.add(new int[]{nx, ny});
                }
            }
            return null;
        }

        private String bestSafeMove(List<String> candidates, int hx, int hy, List<int[]> myBody,
                                   Set<Long> otherBodies, Set<Long> hazards, int width, int height, int length) {
            if (candidates.size() == 1) return candidates.get(0);

            int bestScore = -1;
            String best = candidates.get(0);
            for (String move : candidates) {
                int nx = hx, ny = hy;
                switch (move) {
                    case "up":    ny = hy + 1; break;
                    case "down":  ny = hy - 1; break;
                    case "left":  nx = hx - 1; break;
                    case "right": nx = hx + 1; break;
                }
                List<int[]> bodyAfter = new ArrayList<>();
                bodyAfter.add(new int[]{nx, ny});
                for (int i = 0; i < myBody.size() - 1; i++) bodyAfter.add(myBody.get(i));
                int tailX = bodyAfter.get(bodyAfter.size() - 1)[0];
                int tailY = bodyAfter.get(bodyAfter.size() - 1)[1];
                Set<Long> blocked = new HashSet<>(otherBodies);
                blocked.addAll(hazards);
                for (int i = 0; i < bodyAfter.size() - 1; i++) {
                    int[] p = bodyAfter.get(i);
                    blocked.add(toKey(p[0], p[1]));
                }
                int distToTail = bfsDist(nx, ny, tailX, tailY, blocked, width, height);
                int score = distToTail >= 0 ? distToTail : -1;
                if (length > 5) score = distToTail;
                if (score > bestScore) {
                    bestScore = score;
                    best = move;
                }
            }
            return best;
        }

        private static long toKey(int x, int y) {
            return (long) x << 32 | (y & 0xFFFFFFFFL);
        }

        public void avoidMyNeck(JsonNode head, JsonNode body, ArrayList<String> possibleMoves) {
            if (body.size() < 2) return;
            JsonNode neck = body.get(1);
            int hx = head.get("x").asInt(), hy = head.get("y").asInt();
            int nx = neck.get("x").asInt(), ny = neck.get("y").asInt();
            if (nx < hx) possibleMoves.remove("left");
            else if (nx > hx) possibleMoves.remove("right");
            else if (ny < hy) possibleMoves.remove("down");
            else if (ny > hy) possibleMoves.remove("up");
        }

        public Map<String, String> end(JsonNode endRequest) {
            LOG.info("END");
            return EMPTY;
        }
    }
}

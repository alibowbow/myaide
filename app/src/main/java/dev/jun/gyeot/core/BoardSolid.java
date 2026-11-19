package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BoardSolid {
    private BoardSolid() {}

    public static final List<String> KINDS =
            List.of(
                    "cube",
                    "box",
                    "prism",
                    "pyramid",
                    "tetrahedron",
                    "cylinder",
                    "cone",
                    "sphere",
                    "mesh");
    public static final String[] NAMES = {
        "정육면체", "직육면체", "삼각기둥", "사각뿔", "삼각뿔", "원기둥", "원뿔", "구", "사용자 정의 다면체"
    };

    public static String name(String kind) {
        int i = KINDS.indexOf(kind);
        return i < 0 ? "입체도형" : NAMES[i];
    }

    private record Mesh(List<double[]> vertices, List<int[]> faces) {}

    private static Mesh mesh(JSONObject spec, String kind) {
        List<double[]> v = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();
        if (kind.equals("mesh")) {
            JSONArray points = spec.optJSONArray("vertices"), rings = spec.optJSONArray("faces");
            if (points == null
                    || points.length() < 4
                    || points.length() > 40
                    || rings == null
                    || rings.length() < 4
                    || rings.length() > 40)
                throw new IllegalArgumentException("입체의 꼭짓점과 면을 네 개 이상 입력하세요.");
            for (int i = 0; i < points.length(); i++) {
                JSONArray p = points.optJSONArray(i);
                if (p == null || p.length() != 3)
                    throw new IllegalArgumentException("꼭짓점은 x,y,z 좌표가 필요해요.");
                double[] xyz = {p.optDouble(0), p.optDouble(1), p.optDouble(2)};
                for (double x : xyz)
                    if (!Double.isFinite(x) || Math.abs(x) > 10000)
                        throw new IllegalArgumentException("입체 좌표 범위를 확인하세요.");
                v.add(xyz);
            }
            for (int i = 0; i < rings.length(); i++) {
                JSONArray ring = rings.optJSONArray(i);
                if (ring == null || ring.length() < 3 || ring.length() > 20)
                    throw new IllegalArgumentException("면에는 꼭짓점 번호가 세 개 이상 필요해요.");
                int[] indices = new int[ring.length()];
                for (int j = 0; j < indices.length; j++) {
                    indices[j] = ring.optInt(j, -1);
                    if (indices[j] < 0 || indices[j] >= v.size() || ring.optDouble(j) != indices[j])
                        throw new IllegalArgumentException("면의 꼭짓점 번호가 범위를 벗어났어요.");
                }
                faces.add(indices);
            }
        } else {
            int sides =
                    switch (kind) {
                        case "tetrahedron", "prism" -> 3;
                        case "pyramid" -> 4;
                        default -> 16;
                    };
            boolean tip = List.of("cone", "pyramid", "tetrahedron").contains(kind);
            for (int layer = 0; layer < 2; layer++) {
                if (layer == 1 && tip) {
                    v.add(new double[] {0, 1, 0});
                    break;
                }
                for (int i = 0; i < sides; i++) {
                    double angle = 2 * Math.PI * i / sides;
                    v.add(new double[] {Math.cos(angle), layer == 0 ? -1 : 1, Math.sin(angle)});
                }
            }
            int[] base = new int[sides], top = new int[sides];
            for (int i = 0; i < sides; i++) {
                int j = (i + 1) % sides;
                base[i] = i;
                top[i] = sides + i;
                faces.add(tip ? new int[] {i, j, sides} : new int[] {i, j, sides + j, sides + i});
            }
            faces.add(base);
            if (!tip) faces.add(top);
        }
        return new Mesh(v, faces);
    }

    private static float[] project(double x, double y, double z, double yaw, double pitch) {
        double d = -x * Math.sin(yaw) + z * Math.cos(yaw);
        return new float[] {
            500 + (float) (x * Math.cos(yaw) + z * Math.sin(yaw)),
            245 - (float) (y * Math.cos(pitch) - d * Math.sin(pitch)),
            (float) (y * Math.sin(pitch) + d * Math.cos(pitch))
        };
    }

    static BoardPlot.Made draw(JSONObject spec, float scale) {
        String kind = spec.optString("kind");
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("지원하는 입체 종류를 선택하세요.");
        if (spec.optBoolean("net")) throw new IllegalArgumentException("전개도는 정육면체·직육면체에서 열 수 있어요.");
        double yaw = Math.toRadians(spec.optDouble("yaw", 30)),
                pitch = Math.toRadians(spec.optDouble("pitch", 24));
        List<JSONObject> items = new ArrayList<>();
        if (kind.equals("sphere")) {
            items.add(
                    Board.filled(
                            Board.shape(
                                    Board.OVAL, 326, 71, 674, 419, "blue", 4, false, Board.AI)));
            for (int circle = 0; circle < 3; circle++) {
                for (int i = 0; i < 90; i++) {
                    float[][] s = new float[2][];
                    for (int j = 0; j < 2; j++) {
                        double a = (i + j) * Math.PI * 2 / 90,
                                co = 174 * Math.cos(a),
                                si = 174 * Math.sin(a);
                        s[j] =
                                project(
                                        circle == 1 ? 0 : co,
                                        circle == 0 ? 0 : si,
                                        circle == 0 ? si : circle == 1 ? co : 0,
                                        yaw,
                                        pitch);
                    }
                    if (s[0][2] + s[1][2] >= 0)
                        items.add(
                                Board.shape(
                                        Board.LINE,
                                        s[0][0],
                                        s[0][1],
                                        s[1][0],
                                        s[1][1],
                                        "blue",
                                        3,
                                        false,
                                        Board.AI));
                }
            }
        } else {
            Mesh mesh = mesh(spec, kind);
            double[] lo = {1e20, 1e20, 1e20}, hi = {-1e20, -1e20, -1e20};
            for (double[] p : mesh.vertices)
                for (int i = 0; i < 3; i++) {
                    lo[i] = Math.min(lo[i], p[i]);
                    hi[i] = Math.max(hi[i], p[i]);
                }
            double size = Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]));
            if (size < 1e-9) throw new IllegalArgumentException("입체의 크기가 0이에요.");
            List<float[]> points = new ArrayList<>();
            for (double[] p : mesh.vertices)
                points.add(
                        project(
                                (p[0] - (hi[0] + lo[0]) / 2) * 265 / size,
                                (p[1] - (hi[1] + lo[1]) / 2) * 265 / size,
                                (p[2] - (hi[2] + lo[2]) / 2) * 265 / size,
                                yaw,
                                pitch));
            mesh.faces.sort(
                    Comparator.comparingDouble(
                            face -> {
                                double depth = 0;
                                for (int j : face) depth += points.get(j)[2];
                                return depth / face.length;
                            }));
            for (int[] face : mesh.faces) {
                float[] xy = new float[(face.length + 1) * 2];
                double cx = 0, cy = 0, cz = 0;
                for (int i = 0; i <= face.length; i++) {
                    float[] p = points.get(face[i % face.length]);
                    xy[2 * i] = p[0];
                    xy[2 * i + 1] = p[1];
                    if (i < face.length) {
                        cx += p[0] - 500;
                        cy += p[1] - 245;
                        cz += p[2];
                    }
                }
                float[] a = points.get(face[0]), b = points.get(face[1]), c = points.get(face[2]);
                double ux = b[0] - a[0],
                        uy = b[1] - a[1],
                        uz = b[2] - a[2],
                        vx = c[0] - a[0],
                        vy = c[1] - a[1],
                        vz = c[2] - a[2];
                double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                if (!kind.equals("mesh") && nz * (nx * cx + ny * cy + nz * cz) < 0) continue;
                JSONObject polygon =
                        Board.filled(
                                Board.stroke(
                                        face.length > 4 ? "purple" : "blue",
                                        3,
                                        false,
                                        xy,
                                        Board.AI));
                put(polygon, "sharp", true);
                items.add(polygon);
            }
        }
        items.add(
                Board.text(
                        Board.MARGIN,
                        495,
                        name(kind),
                        Math.max(24, Board.SMALL * scale),
                        "ink",
                        true,
                        895,
                        Board.AI));
        return new BoardPlot.Made(items, 552);
    }
}

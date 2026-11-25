package org.example.canvasproject;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Геометрия рамки выделения.
 *
 * <p>Все координаты — в системе холста, а не экрана: вызывающий код переводит
 * координаты события через {@link #toCanvas(Group, double, double)}. Поэтому
 * рамка одинаково работает при любом масштабе и смещении.
 */
public final class SelectionGeometry {

    /** Порог в экранных пикселях: меньше — это клик, а не протягивание. */
    public static final double DRAG_THRESHOLD = 3.5;

    private static final double EPS = 1e-9;

    private SelectionGeometry() {}

    public static Point2D toCanvas(Group canvasGroup, double sceneX, double sceneY) {
        return canvasGroup.sceneToLocal(sceneX, sceneY);
    }

    public static Bounds normalize(double x1, double y1, double x2, double y2) {
        return new BoundingBox(Math.min(x1, x2), Math.min(y1, y2),
                Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    /** Слева направо — «целиком внутри», справа налево — «касание». */
    public static boolean isContainMode(double startX, double endX) {
        return endX >= startX;
    }

    /** Элементы, попадающие в рамку. Порядок — как в модели (z-порядок). */
    public static List<Selectable> select(BoardModel model, Bounds frame, boolean containMode) {
        List<Selectable> result = new ArrayList<>();
        if (model == null || frame == null) return result;
        for (Selectable el : model.queryIn(frame)) {
            if (hits(el, frame, containMode)) result.add(el);
        }
        return result;
    }

    public static boolean hits(Selectable element, Bounds frame, boolean containMode) {
        if (element == null || frame == null) return false;
        Bounds bounds = element.getSelectBounds();
        if (bounds == null) return false;

        if (element instanceof lineElement line) {
            return hitsLine(line, frame, containMode);
        }
        if (element instanceof lineDrawable line) {
            return hitsDrawableLine(line, frame, containMode);
        }

        // Для остальных типов достаточно их рамки: фигуры, текст, картинки,
        // стикеры и стрелки выделяются по видимым границам.
        return containMode ? contains(frame, bounds) : intersects(frame, bounds);
    }

    public static boolean contains(Bounds outer, Bounds inner) {
        return inner.getMinX() >= outer.getMinX() - EPS
                && inner.getMinY() >= outer.getMinY() - EPS
                && inner.getMaxX() <= outer.getMaxX() + EPS
                && inner.getMaxY() <= outer.getMaxY() + EPS;
    }

    public static boolean intersects(Bounds a, Bounds b) {
        return a.getMaxX() >= b.getMinX() - EPS
                && a.getMinX() <= b.getMaxX() + EPS
                && a.getMaxY() >= b.getMinY() - EPS
                && a.getMinY() <= b.getMaxY() + EPS;
    }

    private static boolean hitsLine(lineElement line, Bounds frame, boolean containMode) {
        int count = line.pointCount();
        if (count == 0) return false;

        // Сначала дешёвая проверка bounding box: отсекает большинство линий.
        Bounds lineBounds = line.getSelectBounds();
        if (lineBounds == null || !intersects(frame, lineBounds)) return false;

        double lx = line.getLayoutX(), ly = line.getLayoutY();
        double[] xs = new double[count];
        double[] ys = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = line.getPoints().get(i * 2) + lx;
            ys[i] = line.getPoints().get(i * 2 + 1) + ly;
            if (containMode && !pointInside(frame, xs[i], ys[i])) return false;
        }
        if (containMode) return true;

        for (int i = 0; i < count; i++) {
            if (pointInside(frame, xs[i], ys[i])) return true;
        }
        for (int i = 0; i + 1 < count; i++) {
            if (segmentIntersectsRect(xs[i], ys[i], xs[i + 1], ys[i + 1], frame)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hitsDrawableLine(lineDrawable line, Bounds frame, boolean containMode) {
        List<Point2D> points = line.getPoints();
        if (points == null || points.isEmpty()) return false;
        Bounds lineBounds = line.getSelectBounds();
        if (lineBounds == null || !intersects(frame, lineBounds)) return false;
        if (containMode) {
            for (Point2D p : points) {
                if (!pointInside(frame, p.getX(), p.getY())) return false;
            }
            return true;
        }
        for (Point2D p : points) {
            if (pointInside(frame, p.getX(), p.getY())) return true;
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            Point2D a = points.get(i), b = points.get(i + 1);
            if (segmentIntersectsRect(a.getX(), a.getY(), b.getX(), b.getY(), frame)) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointInside(Bounds r, double x, double y) {
        return x >= r.getMinX() - EPS && x <= r.getMaxX() + EPS
                && y >= r.getMinY() - EPS && y <= r.getMaxY() + EPS;
    }

    private static boolean segmentIntersectsRect(double x1, double y1,
                                                 double x2, double y2, Bounds r) {
        if (pointInside(r, x1, y1) || pointInside(r, x2, y2)) return true;

        double minX = r.getMinX(), maxX = r.getMaxX();
        double minY = r.getMinY(), maxY = r.getMaxY();
        return segmentsIntersect(x1, y1, x2, y2, minX, minY, maxX, minY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, minY, maxX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, maxX, maxY, minX, maxY)
                || segmentsIntersect(x1, y1, x2, y2, minX, maxY, minX, minY);
    }

    private static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                             double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);

        if (((d1 > EPS && d2 < -EPS) || (d1 < -EPS && d2 > EPS))
                && ((d3 > EPS && d4 < -EPS) || (d3 < -EPS && d4 > EPS))) {
            return true;
        }
        return Math.abs(d1) <= EPS && onSegment(cx, cy, dx, dy, ax, ay)
                || Math.abs(d2) <= EPS && onSegment(cx, cy, dx, dy, bx, by)
                || Math.abs(d3) <= EPS && onSegment(ax, ay, bx, by, cx, cy)
                || Math.abs(d4) <= EPS && onSegment(ax, ay, bx, by, dx, dy);
    }

    private static double cross(double ax, double ay, double bx, double by,
                                double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    private static boolean onSegment(double ax, double ay, double bx, double by,
                                     double px, double py) {
        return px >= Math.min(ax, bx) - EPS && px <= Math.max(ax, bx) + EPS
                && py >= Math.min(ay, by) - EPS && py <= Math.max(ay, by) + EPS;
    }

    public static Bounds unionBounds(Collection<? extends Selectable> elements) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Selectable el : elements) {
            if (el == null) continue;
            Bounds b = el.getSelectBounds();
            if (b == null) continue;
            any = true;
            minX = Math.min(minX, b.getMinX());
            minY = Math.min(minY, b.getMinY());
            maxX = Math.max(maxX, b.getMaxX());
            maxY = Math.max(maxY, b.getMaxY());
        }
        if (!any) return null;
        return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
    }
}

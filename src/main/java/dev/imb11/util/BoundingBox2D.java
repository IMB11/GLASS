package dev.imb11.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import qouteall.imm_ptl.core.portal.GeometryPortalShape;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.HashMap;

public class BoundingBox2D {
    private int min1, min2;
    private int max1, max2;
    private final String plane; // e.g., "XY", "XZ", "YZ"
    private final int fixedCoord;
    private final HashMap<Vec3d, Integer> distanceMap = new HashMap<>();

    @Override
    public String toString() {
        return "BoundingBox2D{" +
                "min1=" + min1 +
                ", min2=" + min2 +
                ", max1=" + max1 +
                ", max2=" + max2 +
                ", plane='" + plane + '\'' +
                ", fixedCoord=" + fixedCoord +
                "} where width = '" + getWidth() + "' and height = '" + getHeight() + "'";
    }

    public void addSquares(BlockPos rootPos, Portal portal, int targetDistance) {
        // portalShape.addTriangleForRectangle(double x1, double y1, double x2, double y2);
        portal.specialShape = new GeometryPortalShape();
        for (var entry : distanceMap.entrySet()) {
            var pos = entry.getKey();
            var distance = entry.getValue();
            var relativePos = pos.subtract(Vec3d.of(rootPos));
            var x = relativePos.x;
            var y = relativePos.y;

            if (distance <= targetDistance) {
                portal.specialShape.addTriangleForRectangle(x, y, x + 1, y + 1);
            }
        }

        // Add rootPos to the portal
        portal.specialShape.addTriangleForRectangle(0, 0, 1, 1);

        portal.specialShape.normalize(this.getWidth(), this.getHeight());
    }

    public BoundingBox2D(BlockPos initialPos, Direction direction) {
        switch (direction) {
            case NORTH:
            case SOUTH:
                plane = "XY";
                min1 = max1 = initialPos.getX();
                min2 = max2 = initialPos.getY();
                fixedCoord = initialPos.getZ();
                break;
            case EAST:
            case WEST:
                plane = "XY";
                min1 = max1 = initialPos.getX();
                min2 = max2 = initialPos.getY();
                fixedCoord = initialPos.getY();
                break;
            case UP:
            case DOWN:
                plane = "XZ";
                min1 = max1 = initialPos.getX();
                min2 = max2 = initialPos.getZ();
                fixedCoord = initialPos.getY();
                break;
            default:
                throw new IllegalArgumentException("Unsupported direction: " + direction);
        }
    }

    public void addBlockPos(BlockPos pos, int distance) {
        int coord1, coord2;

        switch (plane) {
            case "XZ":
                coord1 = pos.getX();
                coord2 = pos.getZ();
                if (pos.getY() != fixedCoord) return;
                break;
            case "XY":
                coord1 = pos.getX();
                coord2 = pos.getY();
                if (pos.getZ() != fixedCoord) return;
                break;
            default:
                return;
        }

        this.distanceMap.put(Vec3d.of(pos), distance);

        min1 = Math.min(min1, coord1);
        max1 = Math.max(max1, coord1);
        min2 = Math.min(min2, coord2);
        max2 = Math.max(max2, coord2);
    }

    public int getWidth() {
        return max1 - min1 + 1;
    }

    public int getHeight() {
        return max2 - min2 + 1;
    }

    public String getPlane() {
        return plane;
    }

    public int getFixedCoord() {
        return fixedCoord;
    }

    public static Vec3d getRelativeUpVector(Direction direction) {
        switch (direction) {
            case NORTH:
            case SOUTH:
            case EAST:
            case WEST:
                return Vec3d.of(Direction.UP.getVector());
            case UP:
                return Vec3d.of(Direction.NORTH.getVector());
            case DOWN:
                return Vec3d.of(Direction.SOUTH.getVector());
            default:
                throw new IllegalArgumentException("Unsupported direction: " + direction);
        }
    }

    public Vec3d getMidpoint() {
        return new Vec3d((min1 + max1) / 2.0, (min2 + max2) / 2.0, fixedCoord);
    }
}
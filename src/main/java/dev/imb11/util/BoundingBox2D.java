package dev.imb11.util;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import qouteall.imm_ptl.core.portal.GeometryPortalShape;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.BoxPortalShape;
import qouteall.imm_ptl.core.portal.shape.PortalShape;
import qouteall.imm_ptl.core.portal.shape.SpecialFlatPortalShape;
import qouteall.q_misc_util.my_util.Mesh2D;

import java.util.ArrayList;
import java.util.HashMap;

import static net.minecraft.util.math.Direction.*;

public class BoundingBox2D {
    private float min1, min2;
    private float max1, max2;
    private final Direction facing;
    private final int fixedCoord;
    private final HashMap<Vec3d, Integer> distanceMap = new HashMap<>();

    @Override
    public String toString() {
        return "BoundingBox2D{" +
                "min1=" + min1 +
                ", min2=" + min2 +
                ", max1=" + max1 +
                ", max2=" + max2 +
                ", facing='" + facing + '\'' +
                ", fixedCoord=" + fixedCoord +
                "} where width = '" + getWidth() + "' and height = '" + getHeight() + "'";
    }

    public void addSquares(BlockPos projectorPos, Portal portal, int targetDistance, boolean flip) {
        var shape = new Mesh2D();
        for (var entry : new ArrayList<>(distanceMap.entrySet()) {{
            this.add(new HashMap.SimpleEntry<>(Vec3d.of(projectorPos), 0));
        }}) {
            var pos = entry.getKey();
            var distance = entry.getValue();
            var relativePos = pos.subtract(getMidpoint());

            double x = 0;
            double y = 0;
            switch (this.facing) {
                case NORTH, SOUTH -> {
                    x = relativePos.x;
                    y = relativePos.y;
                }
                case EAST, WEST -> {
                    x = relativePos.z;
                    y = relativePos.y;
                }
                case UP, DOWN -> {
                    x = relativePos.x;
                    y = relativePos.z;
                }
            }

            if (distance <= targetDistance) {
               shape.addQuad(x, y, x + 1, y + 1);
            }
        }

        var specialShape = new SpecialFlatPortalShape(shape);

        if (flip) {
            specialShape = (SpecialFlatPortalShape) specialShape.getFlipped();
        }

        portal.setPortalShape(specialShape);
    }

    public BoundingBox2D(BlockPos initialPos, Direction direction) {
        switch (direction) {
            case NORTH, SOUTH:
                min1 = max1 = initialPos.getX();
                min2 = max2 = initialPos.getY();
                fixedCoord = initialPos.getZ();
                break;
            case EAST, WEST:
                min1 = max1 = initialPos.getZ();
                min2 = max2 = initialPos.getY();
                fixedCoord = initialPos.getX();
                break;
            case UP, DOWN:
                min1 = max1 = initialPos.getX();
                min2 = max2 = initialPos.getZ();
                fixedCoord = initialPos.getY();
                break;
            default:
                throw new IllegalArgumentException("Unsupported direction: " + direction);
        }
        this.facing = direction;
    }

    public void addBlockPos(BlockPos pos, int distance) {
        float coord1, coord2;

        switch (this.facing) {
            case UP, DOWN:
                coord1 = pos.getX();
                coord2 = pos.getZ();
                if (pos.getY() != fixedCoord) {
                    return;
                }
                break;
            case NORTH, SOUTH:
                coord1 = pos.getX();
                coord2 = pos.getY();
                if (pos.getZ() != fixedCoord) {
                    return;
                }
                break;
            case EAST, WEST:
                coord1 = pos.getZ();
                coord2 = pos.getY();
                if (pos.getX() != fixedCoord) {
                    return;
                }
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

    public float getWidth() {
        return max1 - min1 + 1;
    }

    public float getHeight() {
        return max2 - min2 + 1;
    }

    public Direction getFacing() {
        return this.facing;
    }

    public int getFixedCoord() {
        return fixedCoord;
    }

    public static Vec3d getRelativeUpVector(Direction direction) {
        return switch (direction) {
            case NORTH, SOUTH, EAST, WEST -> Vec3d.of(UP.getVector());
            case UP -> Vec3d.of(Direction.NORTH.getVector());
            case DOWN -> Vec3d.of(SOUTH.getVector());
        };
    }

    public Vec3d getMidpoint() {
        return switch (this.facing) {
            case NORTH, SOUTH -> new Vec3d(min1 + getWidth() / 2, min2 + getHeight() / 2, this.facing == SOUTH ? fixedCoord + 1 : fixedCoord);
            case UP, DOWN -> new Vec3d(min1 + getWidth() / 2, this.facing == UP ? fixedCoord + 1 : fixedCoord, min2 + getHeight() / 2);
            case EAST, WEST -> new Vec3d(this.facing == EAST ? fixedCoord + 1 : fixedCoord, min2 + getHeight() / 2, min1 + getWidth() / 2);
        };
    }
}
package dev.imb11.client.renderer.projection;

import net.minecraft.client.Camera;
import net.minecraft.world.level.ChunkPos;
import dev.imb11.projection.ProjectionChunkRegion;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class ProjectionCamera extends Camera {
    private static final float NEAR_DISTANCE = 0.05F;
    private float horizontalRadius = NEAR_DISTANCE;
    private float verticalRadius = NEAR_DISTANCE;
    private ChunkPos gridCenter;

    public ChunkPos retainGridCenter(Vec3 position) {
        if (gridCenter == null || Math.abs(position.x - gridCenter.getMiddleBlockX()) > 16.0D
                || Math.abs(position.z - gridCenter.getMiddleBlockZ()) > 16.0D) {
            gridCenter = ProjectionChunkRegion.cameraCenter(position);
        }
        return gridCenter;
    }

    public ChunkPos gridCenter() {
        return gridCenter == null ? ProjectionChunkRegion.cameraCenter(getPosition()) : gridCenter;
    }

    public void setPose(Vec3 position, Quaternionf pose) {
        Quaternionf normalizedPose = new Quaternionf(pose).normalize();
        Vector3f look = new Vector3f(0.0F, 0.0F, -1.0F).rotate(normalizedPose);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
        float pitch = (float) Math.toDegrees(Math.asin(-Math.max(-1.0F, Math.min(1.0F, look.y))));
        setRotation(yaw, pitch);
        rotation().set(normalizedPose);
        getLookVector().set(0.0F, 0.0F, -1.0F).rotate(normalizedPose);
        getUpVector().set(0.0F, 1.0F, 0.0F).rotate(normalizedPose);
        getLeftVector().set(-1.0F, 0.0F, 0.0F).rotate(normalizedPose);
        setPosition(position.x, position.y, position.z);
    }

    public void setProjection(Matrix4f projection) {
        horizontalRadius = NEAR_DISTANCE / Math.max(Math.abs(projection.m00()), 1.0E-4F);
        verticalRadius = NEAR_DISTANCE / Math.max(Math.abs(projection.m11()), 1.0E-4F);
    }

    @Override
    public NearPlane getNearPlane() {
        Vec3 center = new Vec3(getLookVector()).scale(NEAR_DISTANCE);
        Vec3 horizontal = new Vec3(getLeftVector()).scale(horizontalRadius);
        Vec3 vertical = new Vec3(getUpVector()).scale(verticalRadius);
        return new NearPlane(center, horizontal, vertical);
    }
}

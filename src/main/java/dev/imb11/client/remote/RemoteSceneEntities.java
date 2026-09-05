package dev.imb11.client.remote;

import com.mojang.authlib.GameProfile;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

final class RemoteSceneEntities {
    private final ClientLevel level;
    private final Set<Integer> vanillaEntities = new HashSet<>();
    private int applying;
    private final Map<Integer, LinkedHashSet<RemoteSubscriptionId>> owners = new LinkedHashMap<>();
    private final Map<Integer, int[]> passengers = new LinkedHashMap<>();

    RemoteSceneEntities(ClientLevel level) {
        this.level = level;
    }

    void apply(RemoteSubscriptionId subscription, List<S2CRemoteEntitiesPacket.EntityMessage> messages) {
        applying++;
        try {
            for (S2CRemoteEntitiesPacket.EntityMessage message : messages) {
                int id = message.entityId();
                Packet<?> packet = message.packet();
                if (packet instanceof ClientboundRemoveEntitiesPacket removal) {
                    for (int removed : removal.getEntityIds()) {
                        release(subscription, removed);
                    }
                    continue;
                }
                if ((packet instanceof ClientboundAddEntityPacket || packet instanceof ClientboundAddExperienceOrbPacket)
                        && !owners.containsKey(id) && level == Minecraft.getInstance().level && level.getEntity(id) != null) {
                    vanillaEntities.add(id);
                }
                if (packet instanceof ClientboundAddEntityPacket spawn) {
                    if (owners.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(subscription) && level.getEntity(id) == null) {
                        Entity entity;
                        if (spawn.getType() == EntityType.PLAYER) {
                            PlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(spawn.getUUID());
                            entity = new RemotePlayer(level, info == null ? new GameProfile(spawn.getUUID(), "Player") : info.getProfile());
                        } else {
                            entity = spawn.getType().create(level);
                        }
                        if (entity != null) {
                            entity.recreateFromPacket(spawn);
                            entity.setOldPosAndRot();
                            level.addEntity(entity);
                        }
                    }
                    continue;
                }
                if (packet instanceof ClientboundAddExperienceOrbPacket spawn) {
                    if (owners.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(subscription) && level.getEntity(id) == null) {
                        ExperienceOrb entity = new ExperienceOrb(level, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getValue());
                        entity.setId(spawn.getId());
                        entity.syncPacketPositionCodec(spawn.getX(), spawn.getY(), spawn.getZ());
                        entity.setOldPosAndRot();
                        level.addEntity(entity);
                    }
                    continue;
                }
                LinkedHashSet<RemoteSubscriptionId> subscriptions = owners.get(id);
                Entity entity = level.getEntity(id);
                if (entity == null || vanillaEntities.contains(id) || subscriptions == null || !subscriptions.contains(subscription)) {
                    continue;
                }
                if ((packet instanceof ClientboundAnimatePacket || packet instanceof ClientboundHurtAnimationPacket
                        || packet instanceof ClientboundEntityEventPacket) && !subscriptions.getFirst().equals(subscription)) {
                    continue;
                }
                if (packet instanceof ClientboundSetEntityDataPacket data) {
                    entity.getEntityData().assignValues(data.packedItems());
                } else if (packet instanceof ClientboundMoveEntityPacket movement) {
                    if (movement.hasPosition()) {
                        Vec3 position = entity.getPositionCodec().decode(movement.getXa(), movement.getYa(), movement.getZa());
                        entity.getPositionCodec().setBase(position);
                        entity.lerpTo(position.x, position.y, position.z,
                                movement.hasRotation() ? movement.getyRot() * 360.0F / 256.0F : entity.lerpTargetYRot(),
                                movement.hasRotation() ? movement.getxRot() * 360.0F / 256.0F : entity.lerpTargetXRot(), 3);
                    } else if (movement.hasRotation()) {
                        entity.lerpTo(entity.lerpTargetX(), entity.lerpTargetY(), entity.lerpTargetZ(),
                                movement.getyRot() * 360.0F / 256.0F, movement.getxRot() * 360.0F / 256.0F, 3);
                    }
                    entity.setOnGround(movement.isOnGround());
                } else if (packet instanceof ClientboundTeleportEntityPacket teleport) {
                    entity.syncPacketPositionCodec(teleport.getX(), teleport.getY(), teleport.getZ());
                    entity.lerpTo(teleport.getX(), teleport.getY(), teleport.getZ(),
                            teleport.getyRot() * 360.0F / 256.0F, teleport.getxRot() * 360.0F / 256.0F, 3);
                    entity.setOnGround(teleport.isOnGround());
                } else if (packet instanceof ClientboundRotateHeadPacket head) {
                    entity.lerpHeadTo(head.getYHeadRot() * 360.0F / 256.0F, 3);
                } else if (packet instanceof ClientboundSetEntityMotionPacket motion) {
                    entity.lerpMotion(motion.getXa(), motion.getYa(), motion.getZa());
                } else if (packet instanceof ClientboundSetEquipmentPacket equipment && entity instanceof LivingEntity living) {
                    equipment.getSlots().forEach(slot -> living.setItemSlot(slot.getFirst(), slot.getSecond()));
                } else if (packet instanceof ClientboundUpdateAttributesPacket attributes && entity instanceof LivingEntity living) {
                    for (ClientboundUpdateAttributesPacket.AttributeSnapshot snapshot : attributes.getValues()) {
                        var attribute = living.getAttributes().getInstance(snapshot.attribute());
                        if (attribute != null) {
                            attribute.setBaseValue(snapshot.base());
                            attribute.removeModifiers();
                            snapshot.modifiers().forEach(attribute::addTransientModifier);
                        }
                    }
                } else if (packet instanceof ClientboundSetPassengersPacket riding) {
                    passengers.put(riding.getVehicle(), riding.getPassengers());
                } else if (packet instanceof ClientboundSetEntityLinkPacket leash && entity instanceof Leashable leashable) {
                    leashable.setDelayedLeashHolderId(leash.getDestId());
                } else if (packet instanceof ClientboundAnimatePacket animation) {
                    if (entity instanceof LivingEntity living && (animation.getAction() == 0 || animation.getAction() == 3)) {
                        living.swing(animation.getAction() == 0 ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                    } else if (entity instanceof Player player && animation.getAction() == 2) {
                        player.stopSleepInBed(false, false);
                    }
                } else if (packet instanceof ClientboundHurtAnimationPacket hurt) {
                    entity.animateHurt(hurt.yaw());
                } else if (packet instanceof ClientboundEntityEventPacket event) {
                    entity.handleEntityEvent(event.getEventId());
                } else if (packet instanceof ClientboundUpdateMobEffectPacket effect && entity instanceof LivingEntity living) {
                    MobEffectInstance instance = new MobEffectInstance(effect.getEffect(), effect.getEffectDurationTicks(),
                            effect.getEffectAmplifier(), effect.isEffectAmbient(), effect.isEffectVisible(), effect.effectShowsIcon());
                    if (!effect.shouldBlend()) {
                        instance.skipBlending();
                    }
                    living.forceAddEffect(instance, null);
                } else if (packet instanceof ClientboundRemoveMobEffectPacket effect && entity instanceof LivingEntity living) {
                    living.removeEffect(effect.effect());
                }
            }
            resolvePassengers();
        } finally {
            applying--;
        }
    }

    void onVanillaAdded(Entity entity) {
        if (applying > 0 || !owners.containsKey(entity.getId())) {
            return;
        }
        vanillaEntities.add(entity.getId());
        Entity existing = level.getEntity(entity.getId());
        if (existing != null && existing != entity) {
            removeEntity(entity.getId());
        }
    }

    boolean onVanillaRemoved(int id) {
        if (applying > 0 || !owners.containsKey(id)) {
            return false;
        }
        vanillaEntities.remove(id);
        return true;
    }

    private void removeEntity(int id) {
        applying++;
        try {
            level.removeEntity(id, Entity.RemovalReason.UNLOADED_TO_CHUNK);
        } finally {
            applying--;
        }
    }

    private void resolvePassengers() {
        passengers.forEach((id, ids) -> {
            Entity vehicle = level.getEntity(id);
            if (vehicle == null || vanillaEntities.contains(id)) {
                return;
            }
            List<Entity> expected = new ArrayList<>();
            for (int passengerId : ids) {
                Entity passenger = level.getEntity(passengerId);
                if (passenger != null) {
                    expected.add(passenger);
                }
            }
            if (!vehicle.getPassengers().equals(expected)) {
                vehicle.ejectPassengers();
                expected.forEach(passenger -> passenger.startRiding(vehicle, true));
            }
        });
    }

    void tick() {
        if (level == Minecraft.getInstance().level) {
            return;
        }
        for (int id : List.copyOf(owners.keySet())) {
            Entity entity = level.getEntity(id);
            if (entity != null && !entity.isRemoved() && !entity.isPassenger()) {
                level.tickNonPassenger(entity);
            }
        }
        ((RemoteSceneLevel) level).tickVisualBlockEntities();
    }

    void release(RemoteSubscriptionId subscription) {
        for (int id : List.copyOf(owners.keySet())) {
            release(subscription, id);
        }
    }

    private void release(RemoteSubscriptionId subscription, int id) {
        LinkedHashSet<RemoteSubscriptionId> subscriptions = owners.get(id);
        if (subscriptions != null && subscriptions.remove(subscription) && subscriptions.isEmpty()) {
            owners.remove(id);
            passengers.remove(id);
            if (!vanillaEntities.remove(id)) {
                removeEntity(id);
            }
        }
    }

    void clear() {
        for (int id : owners.keySet()) {
            if (!vanillaEntities.contains(id)) {
                removeEntity(id);
            }
        }
        vanillaEntities.clear();
        owners.clear();
        passengers.clear();
    }
}

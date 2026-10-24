package io.sakurammo.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 资源增长事件。所有「非消耗」的资源变化都会走这里，便于外部插件扩展增益来源。
 */
public class ResourceGainEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String resourceId;
    private final Cause cause;
    private double amount;
    private boolean cancelled;

    public ResourceGainEvent(Player player, String resourceId, double amount, Cause cause) {
        super(false);
        this.player = player;
        this.resourceId = resourceId;
        this.amount = amount;
        this.cause = cause;
    }

    public Player player() {
        return player;
    }

    public String resourceId() {
        return resourceId;
    }

    public Cause cause() {
        return cause;
    }

    public double amount() {
        return amount;
    }

    public void amount(double amount) {
        this.amount = amount;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    /** 资源增长来源 */
    public enum Cause {
        REGEN,
        DAMAGE_TAKEN,
        ATTACK,
        KILL,
        COMMAND,
        PLUGIN
    }
}

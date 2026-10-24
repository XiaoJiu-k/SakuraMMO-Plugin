package io.sakurammo.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * 职业变更完成后触发（选择或切换都算）。
 */
public class ClassChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String oldClassId;
    private final String newClassId;

    public ClassChangeEvent(Player player, String oldClassId, String newClassId) {
        super(false);
        this.player = player;
        this.oldClassId = oldClassId;
        this.newClassId = newClassId;
    }

    public Player player() {
        return player;
    }

    public String oldClassId() {
        return oldClassId;
    }

    public String newClassId() {
        return newClassId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}

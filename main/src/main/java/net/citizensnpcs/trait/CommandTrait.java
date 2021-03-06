package net.citizensnpcs.trait;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.DelegatePersistence;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.persistence.Persister;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.Placeholders;
import net.citizensnpcs.util.Messages;

@TraitName("commandtrait")
public class CommandTrait extends Trait {
    @Persist
    @DelegatePersistence(NPCCommandPersister.class)
    private final Map<String, NPCCommand> commands = Maps.newHashMap();
    @Persist
    @DelegatePersistence(PlayerNPCCommandPersister.class)
    private final Map<UUID, PlayerNPCCommand> cooldowns = Maps.newHashMap();

    public CommandTrait() {
        super("commandtrait");
    }

    public int addCommand(String command, Hand hand, boolean player, boolean op, int cooldown) {
        int id = getNewId();
        commands.put(String.valueOf(id), new NPCCommand(String.valueOf(id), command, hand, player, op, cooldown));
        return id;
    }

    /**
     * Send a brief description of the current state of the trait to the supplied {@link CommandSender}.
     */
    public void describe(CommandSender sender) {
        List<NPCCommand> left = Lists.newArrayList();
        List<NPCCommand> right = Lists.newArrayList();
        for (NPCCommand command : commands.values()) {
            if (command.hand == Hand.LEFT || command.hand == Hand.BOTH) {
                left.add(command);
            }
            if (command.hand == Hand.RIGHT || command.hand == Hand.BOTH) {
                right.add(command);
            }
        }
        String output = "";
        if (left.size() > 0) {
            output += Messaging.tr(Messages.COMMAND_LEFT_HAND_HEADER);
            for (NPCCommand command : left) {
                output += describe(command);
            }
        }
        if (right.size() > 0) {
            output += Messaging.tr(Messages.COMMAND_RIGHT_HAND_HEADER);
            for (NPCCommand command : right) {
                output += describe(command);
            }
        }
        if (output.isEmpty()) {
            output = Messaging.tr(Messages.COMMAND_NO_COMMANDS_ADDED);
        }
        Messaging.send(sender, output);
    }

    private String describe(NPCCommand command) {
        String output = "<br>    - [" + command.id + "]: " + command.command + " [" + command.cooldown + "s]";
        if (command.op) {
            output += " -o";
        }
        if (command.player) {
            output += " -p";
        }
        return output;
    }

    public void dispatch(Player player, Hand hand) {
        for (NPCCommand command : commands.values()) {
            if (command.hand != hand && command.hand != Hand.BOTH)
                continue;
            PlayerNPCCommand info = cooldowns.get(player.getUniqueId());
            if (info != null && !info.canUse(command)) {
                continue;
            }
            command.run(npc, player);
            if (command.cooldown > 0 && info == null) {
                cooldowns.put(player.getUniqueId(), new PlayerNPCCommand(command));
            }
        }
    }

    private int getNewId() {
        int i = 0;
        while (commands.containsKey(String.valueOf(i))) {
            i++;
        }
        return i;
    }

    public boolean hasCommandId(int id) {
        return commands.containsKey(String.valueOf(id));
    }

    public void removeCommandById(int id) {
        commands.remove(String.valueOf(id));
    }

    public static enum Hand {
        BOTH,
        LEFT,
        RIGHT;
    }

    private static class NPCCommand {
        String command;
        int cooldown;
        Hand hand;
        String id;
        boolean op;
        boolean player;

        public NPCCommand(String id, String command, Hand hand, boolean player, boolean op, int cooldown) {
            this.id = id;
            this.command = command;
            this.hand = hand;
            this.player = player;
            this.op = op;
            this.cooldown = cooldown;
        }

        public void run(NPC npc, Player clicker) {
            String interpolatedCommand = Placeholders.replace(command, clicker, npc);
            if (player) {
                boolean wasOp = clicker.isOp();
                if (op) {
                    clicker.setOp(true);
                }
                try {
                    clicker.performCommand(interpolatedCommand);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                if (op) {
                    clicker.setOp(wasOp);
                }
            } else {
                Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), interpolatedCommand);
            }
        }
    }

    private static class NPCCommandPersister implements Persister<NPCCommand> {
        public NPCCommandPersister() {
        }

        @Override
        public NPCCommand create(DataKey root) {
            return new NPCCommand(root.name(), root.getString("command"), Hand.valueOf(root.getString("hand")),
                    Boolean.valueOf(root.getString("player")), Boolean.valueOf(root.getString("op")),
                    root.getInt("cooldown"));
        }

        @Override
        public void save(NPCCommand instance, DataKey root) {
            root.setString("command", instance.command);
            root.setString("hand", instance.hand.name());
            root.setBoolean("player", instance.player);
            root.setBoolean("op", instance.op);
            root.setInt("cooldown", instance.cooldown);
        }
    }

    private static class PlayerNPCCommand {
        @Persist
        Map<String, Long> lastUsed = Maps.newHashMap();

        public PlayerNPCCommand() {
        }

        public PlayerNPCCommand(NPCCommand command) {
            lastUsed.put(command.command, System.currentTimeMillis() / 1000);
        }

        public boolean canUse(NPCCommand command) {
            long currentTimeSec = System.currentTimeMillis() / 1000;
            if (lastUsed.containsKey(command.command)) {
                if (currentTimeSec < lastUsed.get(command.command) + command.cooldown) {
                    return false;
                }
                lastUsed.remove(command.command);
            }
            if (command.cooldown > 0) {
                lastUsed.put(command.command, currentTimeSec);
            }
            return true;
        }
    }

    private static class PlayerNPCCommandPersister implements Persister<PlayerNPCCommand> {
        public PlayerNPCCommandPersister() {
        }

        @Override
        public PlayerNPCCommand create(DataKey root) {
            return PersistenceLoader.load(PlayerNPCCommand.class, root);
        }

        @Override
        public void save(PlayerNPCCommand instance, DataKey root) {
            PersistenceLoader.save(instance, root);
        }
    }
}
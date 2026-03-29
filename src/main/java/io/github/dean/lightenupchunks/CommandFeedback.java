package io.github.dean.lightenupchunks;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

public final class CommandFeedback {
	private CommandFeedback() {
	}

	public static void sendSuccess(CommandSourceStack source, String message, boolean broadcastToOps) {
		Component component = TextComponents.literal(message);
		source.sendSystemMessage(component);
	}
}

package com.alienriquebm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AebmHolograms implements DedicatedServerModInitializer {
	public static final String MOD_ID = "aebm-holograms";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static MinecraftServer globalServer; // Referencia global al servidor
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	@Override
	public void onInitializeServer() {
		LOGGER.info("AEBM-HOLOGRAMS STARTED");

		// Registrar eventos del ciclo de vida del servidor
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			globalServer = server;
			LOGGER.info("Servidor registrado exitosamente.");
		});

		// Programar tareas automáticas
		scheduler.scheduleAtFixedRate(() -> {
			try {
				updateStatistics(null, false);
			} catch (Exception e) {
				LOGGER.error("Error updating statistics", e);
			}
		}, 0, 1, TimeUnit.MINUTES); // Actualizar cada 1 minuto

		// Registrar comandos
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("aebm-holograms")
					.then(CommandManager.literal("topdeaths")
							.executes(context -> {
								updateStatistics(context.getSource(), true);
								return 1; // Éxito
							}))
					.then(CommandManager.literal("start")
							.executes(context -> {
								initializeHolograms(context.getSource());
								return 1; // Éxito
							}))
					.then(CommandManager.literal("delete")
							.executes(context -> {
								deleteHolograms(context.getSource());
								return 1; // Éxito
							})));
		});

	}

	private void deleteHolograms(ServerCommandSource source) {
		try {
			MinecraftServer server = source.getServer();
			String[] tags = { "deaths_title", "deadths_position1", "deadths_position2", "deadths_position3" };

			// Crear un ServerCommandSource silencioso
			ServerCommandSource silentSource = source.withSilent();

			for (String tag : tags) {
				String command = String.format("kill @e[type=minecraft:text_display,tag=%s]", tag);
				server.getCommandManager().executeWithPrefix(silentSource, command);
				LOGGER.info("Holograma con tag '{}' eliminado.", tag);
			}

			source.sendFeedback(() -> Text.literal("Todos los hologramas han sido eliminados."), false);
		} catch (Exception e) {
			LOGGER.error("Error eliminando hologramas: {}", e.getMessage());
			source.sendError(Text.literal("Error al eliminar hologramas."));
		}
	}

	private void initializeHolograms(ServerCommandSource source) {
		MinecraftServer server = source.getServer();

		String direction;
		float yaw = source.getPlayer().getYaw();
		float pitch = source.getPlayer().getPitch();

		// Determinar la dirección cardinal
		if (pitch >= 45) {
			direction = "Abajo";
		} else if (pitch <= -45) {
			direction = "Arriba";
		} else if (yaw > 45 && yaw < 135) {
			direction = "Oeste";
		} else if (yaw >= 135 || yaw < -135) {
			direction = "Norte";
		} else if (yaw >= -135 && yaw < -45) {
			direction = "Este";
		} else {
			direction = "Sur";
		}

		String adjustedYaw = "0f";
		String adjustedPitch = "0f";

		switch (direction) {
			case "Norte":
				adjustedYaw = "0f"; // Mirando al eje Z negativo
				break;
			case "Sur":
				adjustedYaw = "180f"; // Mirando al eje Z positivo
				break;
			case "Este":
				adjustedYaw = "90f"; // Mirando al eje X positivo
				break;
			case "Oeste":
				adjustedYaw = "-90f"; // Mirando al eje X negativo
				break;
			case "Arriba":
				adjustedPitch = "-90f";
				break;
			case "Abajo":
				adjustedPitch = "90f";
				break;
		}

		// Crear hologramas
		createHologramIfMissing(server, source, "deaths_title", getSummonCommand(
				"Top muertes Guareneras", "gold", true, "deaths_title", 2.0, adjustedYaw, adjustedPitch));
		createHologramIfMissing(server, source, "deadths_position1", getSummonCommand(
				"Jugador 1", "white", false, "deadths_position1", 1.5, adjustedYaw, adjustedPitch));
		createHologramIfMissing(server, source, "deadths_position2", getSummonCommand(
				"Jugador 2", "white", false, "deadths_position2", 1.2, adjustedYaw, adjustedPitch));
		createHologramIfMissing(server, source, "deadths_position3", getSummonCommand(
				"Jugador 3", "white", false, "deadths_position3", 0.9, adjustedYaw, adjustedPitch));

		// Actualizar estadísticas inmediatamente
		updateStatistics(source, false);
		LOGGER.info("Hologramas inicializados y actualizados.");
	}

	private String getSummonCommand(String text, String color, boolean bold, String tag, double yOffset, String yaw,
			String pitch) {

		String command = String.format(
				java.util.Locale.US,
				"summon minecraft:text_display ~ ~%.1f ~ {text:'{\"text\":\"%s\",\"color\":\"%s\",\"bold\": %b }',CustomNameVisible:0b,billboard:\"fixed\",background:0b,Rotation:[%s,%s],Tags:[\"%s\"]}",
				yOffset, text, color, bold, yaw, pitch, tag);
		return command;
	}

	private void createHologramIfMissing(MinecraftServer server, ServerCommandSource source, String tag,
			String summonCommand) {
		boolean exists = server.getOverworld().getEntitiesByType(
				EntityType.TEXT_DISPLAY,
				entity -> entity.getCommandTags().contains(tag)).size() > 0;

		if (!exists) {
			try {
				// Crear un ServerCommandSource silencioso
				ServerCommandSource silentSource = source.withSilent();

				// Ejecutar el comando sin verificar el resultado
				server.getCommandManager().executeWithPrefix(silentSource, summonCommand);
				LOGGER.info("Holograma creado con tag: {}", tag);
			} catch (Exception e) {
				LOGGER.error("Error al crear holograma con tag '{}': {}", tag, e.getMessage());
			}
		} else {
			LOGGER.info("Holograma con tag '{}' ya existe.", tag);
		}
	}

	private void updateStatistics(ServerCommandSource source, boolean sendFeedback) {
		List<PlayerDeathStat> playerDeathStats = new ArrayList<>();

		try {
			MinecraftServer server = source != null ? source.getServer() : globalServer;
			if (server == null) {
				LOGGER.error("No se pudo acceder al servidor.");
				return;
			}

			// Crear un ServerCommandSource temporal si no hay uno disponible
			if (source == null) {
				source = new ServerCommandSource(
						new CommandOutput() {
							@Override
							public void sendMessage(Text message) {
								// No hacer nada
							}

							@Override
							public boolean shouldReceiveFeedback() {
								return false;
							}

							@Override
							public boolean shouldTrackOutput() {
								return false;
							}

							@Override
							public boolean shouldBroadcastConsoleToOps() {
								return false;
							}
						},
						Vec3d.ZERO,
						Vec2f.ZERO,
						globalServer.getOverworld(),
						4,
						"DeathsUpdater",
						Text.literal("DeathsUpdater"),
						globalServer,
						null);
			}

			// Crear un ServerCommandSource silencioso
			ServerCommandSource silentSource = source.withSilent();

			// Forzar la escritura de estadísticas
			server.getPlayerManager().saveAllPlayerData();

			// Leer los archivos de estadísticas
			Path statsDirectory = server.getRunDirectory().resolve(server.getSaveProperties()
					.getMainWorldProperties().getLevelName())
					.resolve("stats");

			if (!Files.exists(statsDirectory)) {
				if (sendFeedback) {
					source.sendError(Text.literal("El directorio de estadísticas no existe: " + statsDirectory));
				}
				LOGGER.warn("El directorio de estadísticas no existe: {}", statsDirectory);
				return;
			}

			Files.list(statsDirectory).forEach(path -> {
				try (FileReader reader = new FileReader(path.toFile())) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					JsonObject customStats = json.getAsJsonObject("stats").getAsJsonObject("minecraft:custom");
					int deaths = customStats.has("minecraft:deaths") ? customStats.get("minecraft:deaths").getAsInt()
							: 0;

					String uuid = path.getFileName().toString().replace(".json", "");
					String playerName = server.getUserCache()
							.getByUuid(java.util.UUID.fromString(uuid))
							.map(profile -> profile.getName())
							.orElse("Desconocido");

					playerDeathStats.add(new PlayerDeathStat(playerName, deaths));
				} catch (Exception e) {
					LOGGER.error("Error leyendo el archivo de estadísticas: {}", path, e);
				}
			});

			// Ordenar por muertes y obtener los 3 mejores
			List<PlayerDeathStat> topDeaths = playerDeathStats.stream()
					.sorted(Comparator.comparingInt(PlayerDeathStat::getDeaths).reversed())
					.limit(3)
					.toList();

			// Actualizar los hologramas
			for (int i = 0; i < 3; i++) {
				PlayerDeathStat stat = i < topDeaths.size() ? topDeaths.get(i) : null;
				String tag = String.format("deadths_position%d", i + 1);
				String updateCommand = String.format(
						"data merge entity @e[type=minecraft:text_display, tag=%s, limit=1] {text:'{\"text\":\"%s\",\"color\":\"white\", \"bold\": false}'}",
						tag, stat != null ? stat.getName() + " (" + stat.getDeaths() + " muertes)" : "");
				server.getCommandManager().executeWithPrefix(silentSource, updateCommand);
			}

			// Enviar feedback si es necesario
			if (sendFeedback) {
				StringBuilder feedbackBuilder = new StringBuilder("Top 3 jugadores con más muertes:\n");
				for (int i = 0; i < topDeaths.size(); i++) {
					PlayerDeathStat stat = topDeaths.get(i);
					feedbackBuilder
							.append(String.format("%d. %s - %d muertes\n", i + 1, stat.getName(), stat.getDeaths()));
				}
				final String feedback = feedbackBuilder.toString(); // Convertir a String
				source.sendFeedback(() -> Text.literal(feedback), false);
			}

		} catch (Exception e) {
			LOGGER.error("Error leyendo estadísticas", e);
		}
	}

	private static class PlayerDeathStat {
		private final String name;
		private final int deaths;

		public PlayerDeathStat(String name, int deaths) {
			this.name = name;
			this.deaths = deaths;
		}

		public String getName() {
			return name;
		}

		public int getDeaths() {
			return deaths;
		}
	}
}

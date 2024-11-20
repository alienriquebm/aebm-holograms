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

		// Registrar el comando manual (opcional para depuración)
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(CommandManager.literal("topmuertes").executes(context -> {
				updateStatistics(context.getSource(), true);
				return 1; // Éxito
			}));
		});
	}

	// Obtener la referencia global al servidor
	public static MinecraftServer getGlobalServer() {
		return globalServer;
	}

	// Método principal para actualizar estadísticas
	private void updateStatistics(ServerCommandSource source, boolean sendFeedback) {
		List<PlayerDeathStat> playerDeathStats = new ArrayList<>();

		try {
			MinecraftServer server = source != null ? source.getServer() : getGlobalServer();
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
								// No hacer nada: suprime los mensajes
							}

							@Override
							public boolean shouldReceiveFeedback() {
								return false; // Deshabilitar retroalimentación
							}

							@Override
							public boolean shouldTrackOutput() {
								return false; // Deshabilitar seguimiento de salida
							}

							@Override
							public boolean shouldBroadcastConsoleToOps() {
								return false; // Deshabilitar mensajes a operadores
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

			// Forzar la escritura de estadísticas
			server.getPlayerManager().saveAllPlayerData();

			// Crear hologramas faltantes (igual que antes)
			for (int i = 0; i < 3; i++) {
				String tag = String.format("deadths_position%d", i + 1);
				boolean exists = server.getOverworld().getEntitiesByType(
						EntityType.TEXT_DISPLAY,
						entity -> entity.getCommandTags().contains(tag)).size() > 0;

				if (!exists) {
					LOGGER.info("Created missing text_display entity with tag: {}", tag);
				}
			}

			// Leer los archivos de estadísticas (igual que antes)
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

			ServerCommandSource silentSource = new ServerCommandSource(
					new CommandOutput() {
						@Override
						public void sendMessage(Text message) {
							// No hacer nada: silenciar todos los mensajes
						}

						@Override
						public boolean shouldReceiveFeedback() {
							return false; // No generar retroalimentación
						}

						@Override
						public boolean shouldTrackOutput() {
							return false; // No rastrear salida
						}

						@Override
						public boolean shouldBroadcastConsoleToOps() {
							return false; // No enviar mensajes a operadores
						}
					},
					Vec3d.ZERO,
					Vec2f.ZERO,
					server.getOverworld(),
					4,
					"DeathsUpdater",
					Text.literal("DeathsUpdater"),
					server,
					null);

			// Usar el ServerCommandSource silencioso para ejecutar los comandos
			for (int i = 0; i < 3; i++) {
				PlayerDeathStat stat = i < topDeaths.size() ? topDeaths.get(i) : null;
				String tag = String.format("deadths_position%d", i + 1);
				String updateCommand = String.format(
						"data merge entity @e[type=minecraft:text_display, tag=%s, limit=1] {text:'{\"text\":\"%s\",\"color\":\"white\", \"bold\": false}'}",
						tag, stat != null ? stat.getName() + " (" + stat.getDeaths() + " muertes)" : "");
				server.getCommandManager().executeWithPrefix(silentSource, updateCommand);
			}

			// Enviar feedback al jugador si se pidió
			if (sendFeedback) {
				String feedback = "Top 3 jugadores con más muertes:\n";
				for (int i = 0; i < topDeaths.size(); i++) {
					PlayerDeathStat stat = topDeaths.get(i);
					feedback += String.format("%d. %s - %d muertes\n", i + 1, stat.getName(), stat.getDeaths());
				}
				// Crear una copia inmutable de feedback
				final String feedbackFinal = feedback;
				source.sendFeedback(() -> Text.literal(feedbackFinal), false);
			}

		} catch (Exception e) {
			LOGGER.error("Error leyendo estadísticas", e);
		}
	}

	// Clase interna para estadísticas de muertes
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

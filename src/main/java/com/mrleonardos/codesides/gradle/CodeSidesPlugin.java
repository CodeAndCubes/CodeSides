package com.mrleonardos.codesides.gradle;

import java.util.LinkedHashMap;
import java.util.Map;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * Плагин {@code com.mrleonardos.codesides}: добавляет моду задачу {@code splitSides}, которая собирает из
 * одного исходника два jar (серверный и клиентский), физически вырезая из каждого код и ресурсы чужой
 * стороны.
 *
 * <p>Для Minecraft 1.7.10 резать нужно уже реобфусцированный jar, иначе имена классов Minecraft в сторонах
 * разъедутся с маппингами:
 *
 * <pre>
 * codeSides {
 *     inputJar = tasks.named('reobfJar').flatMap { it.archiveFile }
 * }
 * </pre>
 *
 * Без {@code inputJar} режутся каталоги сборки: годится для модов без реобфускации и для проверок в CI.
 */
public class CodeSidesPlugin implements Plugin<Project>
{
	private static final String EXTENSION_NAME = "codeSides";
	private static final String TASK_NAME = "splitSides";

	@Override
	public void apply(Project project)
	{
		project.getPluginManager().apply(JavaPlugin.class);

		final CodeSidesExtension extension = project.getExtensions().create(EXTENSION_NAME, CodeSidesExtension.class);
		extension.getArchiveBaseName().convention(project.getName());
		extension.getArchiveVersion().convention(project.provider(() -> versionOf(project)));
		extension.getStrict().convention(Boolean.TRUE);
		extension.getServer().convention(Boolean.TRUE);
		extension.getClient().convention(Boolean.TRUE);
		extension.getUniversalJar().convention(Boolean.TRUE);
		extension.getVerifyAgainstClasspath().convention(Boolean.TRUE);
		extension.getDefaultResourceRules().convention(Boolean.TRUE);

		final DirectoryProperty buildDirectory = project.getLayout().getBuildDirectory();
		final TaskProvider<SplitSidesTask> split = project.getTasks().register(TASK_NAME, SplitSidesTask.class,
			task ->
			{
				task.setGroup(LifecycleBasePlugin.BUILD_GROUP);
				task.setDescription("Разрезать мод на серверный и клиентский jar (CodeSides)");
				task.getInputJar().set(extension.getInputJar());
				task.getStrict().set(extension.getStrict());
				task.getServer().set(extension.getServer());
				task.getClient().set(extension.getClient());
				task.getDefaultResourceRules().set(extension.getDefaultResourceRules());
				task.getServerOnlyResources().set(extension.getServerOnlyResources());
				task.getClientOnlyResources().set(extension.getClientOnlyResources());
				task.getServerArchive().set(buildDirectory.file(archivePath(extension, "server")));
				task.getClientArchive().set(buildDirectory.file(archivePath(extension, "client")));
			});

		final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
		final SourceSet main = java.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

		project.afterEvaluate(evaluated ->
		{
			split.configure(task ->
			{
				if (!extension.getInputJar().isPresent())
				{
					task.getClassesDirs().from(main.getOutput().getClassesDirs());
					task.getResourceDirs().from(main.getOutput().getResourcesDir());
					task.dependsOn(evaluated.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
					task.getManifestAttributes().set(manifestAttributes(evaluated));
				}
				if (extension.getVerifyAgainstClasspath().get())
					task.getVerifyClasspath().from(main.getCompileClasspath());
			});

			if (!extension.getUniversalJar().get())
				evaluated.getTasks().named(JavaPlugin.JAR_TASK_NAME).configure(jar -> jar.setEnabled(false));

			evaluated.getTasks().named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)
				.configure(assemble -> assemble.dependsOn(split));
		});
	}

	private static Provider<String> archivePath(CodeSidesExtension extension, String classifier)
	{
		return extension.getArchiveBaseName()
			.flatMap(base -> extension.getArchiveVersion().map(version -> "libs/" + base
				+ (version.isEmpty() ? "" : "-" + version) + "-" + classifier + ".jar"));
	}

	private static String versionOf(Project project)
	{
		String version = String.valueOf(project.getVersion());
		return "unspecified".equals(version) ? "" : version;
	}

	private static Map<String, String> manifestAttributes(Project project)
	{
		Map<String, String> attributes = new LinkedHashMap<>();
		Object jar = project.getTasks().findByName(JavaPlugin.JAR_TASK_NAME);
		if (!(jar instanceof Jar))
			return attributes;
		for (Map.Entry<String, Object> attribute : ((Jar) jar).getManifest().getEffectiveManifest().getAttributes()
			.entrySet())
			if (attribute.getValue() != null)
				attributes.put(attribute.getKey(), String.valueOf(attribute.getValue()));
		return attributes;
	}
}

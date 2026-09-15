package com.mrleonardos.codesides.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitSidesTaskTest
{
	private static final String CLEAN_COMMON = "package mymod;\n"
		+ "public final class Common\n"
		+ "{\n"
		+ "	public String call()\n"
		+ "	{\n"
		+ "		return \"common\";\n"
		+ "	}\n"
		+ "}\n";

	private static final String BROKEN_COMMON = "package mymod;\n"
		+ "public final class Common\n"
		+ "{\n"
		+ "	public String call()\n"
		+ "	{\n"
		+ "		return new ServerThing().secret();\n"
		+ "	}\n"
		+ "}\n";

	@TempDir
	Path project;

	@Test
	void redBuildLeavesNoArchivesInsteadOfAMismatchedPair() throws IOException
	{
		writeProject("");
		write("src/main/java/mymod/Common.java", CLEAN_COMMON);
		assertEquals(TaskOutcome.SUCCESS, run().task(":splitSides").getOutcome());
		assertTrue(archive("server").isFile());
		assertTrue(archive("client").isFile());

		write("src/main/java/mymod/Common.java", BROKEN_COMMON);
		BuildResult broken = runAndFail();

		assertTrue(broken.getOutput().contains("нарушений контракта"), "падение должно быть от верификатора");
		assertFalse(archive("server").isFile(),
			"серверный jar уже пересобран, клиентский падает: пара разъехалась бы, класть её рядом нельзя");
		assertFalse(archive("client").isFile(), "после красной сборки в build/libs не должно остаться сторон");
	}

	@Test
	void disabledSideLeavesNoStaleArchive() throws IOException
	{
		writeProject("");
		write("src/main/java/mymod/Common.java", CLEAN_COMMON);
		run();
		assertTrue(archive("client").isFile());

		writeProject("codeSides {\n\tclient = false\n}\n");
		run();

		assertTrue(archive("server").isFile(), "включённая сторона пишется как обычно");
		assertFalse(archive("client").isFile(), "прошлый клиентский jar не должен пережить client = false");
	}

	@Test
	void inputJarModeCutsTheReadyArchive() throws IOException
	{
		writeProject("codeSides {\n\tinputJar = tasks.named('jar').flatMap { it.archiveFile }\n}\n");
		write("src/main/java/mymod/Common.java", CLEAN_COMMON);

		assertEquals(TaskOutcome.SUCCESS, run().task(":splitSides").getOutcome());
		assertTrue(archive("server").isFile(), "в режиме inputJar стороны пишутся как обычно");
		assertTrue(jarEntry(archive("server"), "mymod/ServerThing.class"),
			"серверная сторона режется из готового jar, своя половина остаётся");
		assertFalse(jarEntry(archive("client"), "mymod/ServerThing.class"),
			"клиентская сторона режется из готового jar, чужая половина уходит");
	}

	private boolean jarEntry(File jar, String entry) throws IOException
	{
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar.toPath())))
		{
			for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry())
				if (e.getName().equals(entry))
					return true;
			return false;
		}
	}

	private BuildResult run()
	{
		return runner().build();
	}

	private BuildResult runAndFail()
	{
		return runner().buildAndFail();
	}

	private GradleRunner runner()
	{
		return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath().withDebug(true)
			.withArguments("splitSides");
	}

	private File archive(String classifier)
	{
		return project.resolve("build/libs/mymod-1.2.3-" + classifier + ".jar").toFile();
	}

	private void writeProject(String extension) throws IOException
	{
		write("settings.gradle", "rootProject.name = 'mymod'\n");
		write("build.gradle", "plugins {\n\tid 'com.mrleonardos.codesides'\n}\n\nversion = '1.2.3'\n\n" + extension);
		write("src/main/java/com/mrleonardos/codesides/ServerSide.java",
			"package com.mrleonardos.codesides;\n\npublic @interface ServerSide\n{\n}\n");
		write("src/main/java/mymod/ServerThing.java", "package mymod;\n\n"
			+ "import com.mrleonardos.codesides.ServerSide;\n\n"
			+ "@ServerSide\n"
			+ "public final class ServerThing\n"
			+ "{\n"
			+ "	public String secret()\n"
			+ "	{\n"
			+ "		return \"server-secret\";\n"
			+ "	}\n"
			+ "}\n");
	}

	private void write(String path, String content) throws IOException
	{
		Path target = project.resolve(path);
		Files.createDirectories(target.getParent());
		Files.write(target, content.getBytes(StandardCharsets.UTF_8));
	}
}

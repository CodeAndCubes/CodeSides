package com.mrleonardos.codesides.gradle;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Manifest;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import com.mrleonardos.codesides.Side;
import com.mrleonardos.codesides.split.ClassLookup;
import com.mrleonardos.codesides.split.ClasspathLookup;
import com.mrleonardos.codesides.split.ModArchive;
import com.mrleonardos.codesides.split.ResourceRules;
import com.mrleonardos.codesides.split.SideSplitter;
import com.mrleonardos.codesides.split.SideVerifier;
import com.mrleonardos.codesides.split.SplitOutput;
import com.mrleonardos.codesides.split.Violation;

/**
 * Режет мод на серверный и клиентский jar: вырезает код и ресурсы чужой стороны, верифицирует результат
 * и пишет архивы в {@code build/libs}.
 */
public abstract class SplitSidesTask extends DefaultTask
{
	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getClassesDirs();

	@InputFiles
	@Optional
	@PathSensitive(PathSensitivity.RELATIVE)
	public abstract ConfigurableFileCollection getResourceDirs();

	@InputFile
	@Optional
	@PathSensitive(PathSensitivity.NONE)
	public abstract RegularFileProperty getInputJar();

	@Classpath
	@Optional
	public abstract ConfigurableFileCollection getVerifyClasspath();

	@Input
	public abstract Property<Boolean> getStrict();

	@Input
	public abstract Property<Boolean> getServer();

	@Input
	public abstract Property<Boolean> getClient();

	@Input
	public abstract Property<Boolean> getDefaultResourceRules();

	@Input
	public abstract ListProperty<String> getServerOnlyResources();

	@Input
	public abstract ListProperty<String> getClientOnlyResources();

	@Input
	public abstract MapProperty<String, String> getManifestAttributes();

	@OutputFile
	@Optional
	public abstract RegularFileProperty getServerArchive();

	@OutputFile
	@Optional
	public abstract RegularFileProperty getClientArchive();

	@TaskAction
	void run() throws IOException
	{
		File serverTarget = getServerArchive().isPresent() ? getServerArchive().get().getAsFile() : null;
		File clientTarget = getClientArchive().isPresent() ? getClientArchive().get().getAsFile() : null;
		discard(serverTarget);
		discard(clientTarget);

		ModArchive source = readSource();
		getLogger().lifecycle("CodeSides: на входе {} классов и {} ресурсов", source.classes.size(),
			source.resources.size());

		ResourceRules rules = ResourceRules.of(getServerOnlyResources().get(), getClientOnlyResources().get(),
			getDefaultResourceRules().get());

		List<Cut> cuts = new ArrayList<>();
		ClasspathLookup lookup = getVerifyClasspath().isEmpty() ? null
			: new ClasspathLookup(getVerifyClasspath().getFiles());
		try
		{
			if (getServer().get())
				cuts.add(cut(source, Side.SERVER, serverTarget, rules, lookup == null ? ClassLookup.EMPTY : lookup));
			if (getClient().get())
				cuts.add(cut(source, Side.CLIENT, clientTarget, rules, lookup == null ? ClassLookup.EMPTY : lookup));
		}
		finally
		{
			if (lookup != null)
				lookup.close();
		}

		for (Cut ready : cuts)
			write(ready, source);
	}

	private ModArchive readSource() throws IOException
	{
		ModArchive source = getInputJar().isPresent() ? ModArchive.read(getInputJar().get().getAsFile())
			: ModArchive.fromDirectories(getClassesDirs().getFiles(), getResourceDirs().getFiles(), null);
		if (source.classes.isEmpty() && source.resources.isEmpty())
			throw new GradleException("CodeSides: нечего резать — на входе ни одного класса и ни одного ресурса. "
				+ "Проверь inputJar и каталоги сборки: до splitSides должна отработать компиляция");
		return source;
	}

	private Cut cut(ModArchive source, Side side, File target, ResourceRules rules, ClassLookup lookup)
	{
		if (target == null)
			throw new GradleException("CodeSides: для стороны " + side + " не задан выходной архив");

		SplitOutput out = SideSplitter.split(source.classes, side);
		List<Violation> violations = SideVerifier.verify(out, lookup);
		if (!violations.isEmpty())
			report(side, violations);

		Map<String, byte[]> resources = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : source.resources.entrySet())
		{
			try
			{
				if (rules.keep(e.getKey(), side))
					resources.put(e.getKey(), e.getValue());
			}
			catch (IllegalArgumentException conflict)
			{
				throw new GradleException("CodeSides: " + conflict.getMessage(), conflict);
			}
		}
		return new Cut(side, target, out, resources);
	}

	private void write(Cut ready, ModArchive source) throws IOException
	{
		ModArchive.write(ready.target, ready.out.classes, ready.resources, manifestFor(source));
		getLogger().lifecycle(
			"CodeSides: {} — {} классов (вырезано целиком {}, методов {}, полей {}), ресурсов {} из {} -> {}",
			ready.side, ready.out.classes.size(), ready.out.removedClasses.size(), ready.out.removedMethods.size(),
			ready.out.removedFields.size(), ready.resources.size(), source.resources.size(), ready.target.getName());
	}

	private void discard(File archive) throws IOException
	{
		if (archive != null && archive.exists() && !archive.delete())
			throw new IOException("не удалось удалить прошлый архив " + archive);
	}

	private void report(Side side, List<Violation> violations)
	{
		StringBuilder message = new StringBuilder();
		message.append("CodeSides: сторона ").append(side).append(" — нарушений контракта: ").append(violations.size())
			.append('\n');
		for (Violation violation : violations)
			message.append("  • ").append(violation).append('\n');
		message.append("Оставшийся код зависит от вырезанного. Спрячь логику стороны за общим интерфейсом, "
			+ "а реализацию пометь @ServerSide/@ClientSide — либо пометь и сам класс-ссылку.");
		if (contains(violations, Violation.KIND_CONSTANT, Violation.KIND_INLINED))
			message.append('\n').append("Константы времени компиляции (static final примитив или String) javac "
				+ "подставляет по месту использования: пометка стороной значение не убирает. Отдавай его из "
				+ "помеченного метода или держи в помеченном классе, к которому общий код не обращается.");
		if (contains(violations, Violation.KIND_INITIALIZER))
			message.append('\n').append("Помеченное поле с инициализатором вырезается только наполовину: "
				+ "присваивание остаётся в <init>/<clinit> вместе с телом лямбды или анонимки. Перенеси "
				+ "инициализацию в помеченный метод или конструктор.");
		if (getStrict().get())
			throw new GradleException(message.toString());
		getLogger().warn(message.toString());
	}

	private static boolean contains(List<Violation> violations, String... kinds)
	{
		for (Violation violation : violations)
			for (String kind : kinds)
				if (kind.equals(violation.kind))
					return true;
		return false;
	}

	private static final class Cut
	{
		private final Side side;
		private final File target;
		private final SplitOutput out;
		private final Map<String, byte[]> resources;

		Cut(Side side, File target, SplitOutput out, Map<String, byte[]> resources)
		{
			this.side = side;
			this.target = target;
			this.out = out;
			this.resources = resources;
		}
	}

	private Manifest manifestFor(ModArchive source)
	{
		if (source.manifest != null)
			return source.manifest;
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
		for (Map.Entry<String, String> attribute : getManifestAttributes().get().entrySet())
			manifest.getMainAttributes().putValue(attribute.getKey(), attribute.getValue());
		return manifest;
	}
}

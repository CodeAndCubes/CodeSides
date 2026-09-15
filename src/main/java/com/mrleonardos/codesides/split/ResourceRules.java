package com.mrleonardos.codesides.split;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.mrleonardos.codesides.Side;

/**
 * Раскладка ресурсов по сторонам: какие файлы из {@code src/main/resources} попадут в клиентский jar,
 * какие в серверный, а какие в оба.
 *
 * <p>Правила: glob-маски по пути внутри jar ({@code assets/mymod/textures/**}), где {@code *} не переходит
 * через {@code /}, а {@code **} переходит. Маска из двух звёзд, стоящая в начале перед именем файла, ловит
 * и файл в корне архива. Файл, не попавший ни под одну маску, считается общим.
 *
 * <p>Маски по умолчанию убирают из серверного jar клиентские медиа: текстуры, модели, звуки, шейдеры.
 * Языковые файлы остаются общими намеренно: сервер тоже переводит строки. Серверных масок по умолчанию нет,
 * приватные данные мода задаются явно.
 */
public final class ResourceRules
{
	/** Клиентские медиа, которым нечего делать в серверном jar. */
	public static final List<String> DEFAULT_CLIENT_ONLY = Collections.unmodifiableList(Arrays.asList(
		"assets/**/textures/**", "assets/**/models/**", "assets/**/shaders/**", "assets/**/sounds/**",
		"assets/**/particles/**", "assets/**/font/**", "assets/**/blockstates/**", "assets/**/sounds.json",
		"assets/**/*.png", "assets/**/*.ogg", "assets/**/*.wav", "assets/**/*.mcmeta", "assets/**/*.obj",
		"assets/**/*.mtl", "assets/**/*.vsh", "assets/**/*.fsh", "assets/**/*.glsl"));

	/** Серверных масок по умолчанию нет: что прятать от игрока, знает только автор мода. */
	public static final List<String> DEFAULT_SERVER_ONLY = Collections.emptyList();

	private final List<Rule> serverOnly;
	private final List<Rule> clientOnly;

	private ResourceRules(List<Rule> serverOnly, List<Rule> clientOnly)
	{
		this.serverOnly = serverOnly;
		this.clientOnly = clientOnly;
	}

	/**
	 * Собрать правила из пользовательских масок.
	 *
	 * @param serverOnly     маски файлов только для серверного jar
	 * @param clientOnly     маски файлов только для клиентского jar
	 * @param withDefaults   добавить ли маски по умолчанию
	 */
	public static ResourceRules of(Collection<String> serverOnly, Collection<String> clientOnly, boolean withDefaults)
	{
		List<Rule> server = compileAll(serverOnly);
		List<Rule> client = compileAll(clientOnly);
		if (withDefaults)
		{
			server.addAll(compileAll(DEFAULT_SERVER_ONLY));
			client.addAll(compileAll(DEFAULT_CLIENT_ONLY));
		}
		return new ResourceRules(server, client);
	}

	/**
	 * Сторона ресурса или {@code null}, если он общий.
	 *
	 * @throws IllegalArgumentException если путь попал и под серверную, и под клиентскую маску
	 */
	public Side sideOf(String path)
	{
		Rule server = firstMatch(serverOnly, path);
		Rule client = firstMatch(clientOnly, path);
		if (server != null && client != null)
			throw new IllegalArgumentException("ресурс " + path + " попал и под серверную маску '" + server.pattern
				+ "', и под клиентскую '" + client.pattern + "'");
		if (server != null)
			return Side.SERVER;
		return client != null ? Side.CLIENT : null;
	}

	/** Попадёт ли ресурс в jar стороны {@code side}. */
	public boolean keep(String path, Side side)
	{
		Side owner = sideOf(path);
		return owner == null || owner == side;
	}

	private static Rule firstMatch(List<Rule> rules, String path)
	{
		for (Rule rule : rules)
			if (rule.regex.matcher(path).matches())
				return rule;
		return null;
	}

	private static List<Rule> compileAll(Collection<String> patterns)
	{
		List<Rule> rules = new ArrayList<>();
		if (patterns != null)
			for (String pattern : patterns)
				rules.add(new Rule(pattern, compile(pattern)));
		return rules;
	}

	private static Pattern compile(String glob)
	{
		StringBuilder regex = new StringBuilder("^");
		for (int i = 0; i < glob.length(); i++)
		{
			char c = glob.charAt(i);
			if (i == 0 && glob.startsWith("**/", i))
			{
				regex.append("(?:.*/)?");
				i += 2;
			}
			else if (c == '/' && glob.startsWith("/**/", i))
			{
				regex.append("/(?:.*/)?");
				i += 3;
			}
			else if (c == '*')
			{
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*')
				{
					regex.append(".*");
					i++;
				}
				else
				{
					regex.append("[^/]*");
				}
			}
			else if (c == '?')
			{
				regex.append("[^/]");
			}
			else if ("\\.[]{}()+-^$|".indexOf(c) >= 0)
			{
				regex.append('\\').append(c);
			}
			else
			{
				regex.append(c);
			}
		}
		return Pattern.compile(regex.append('$').toString());
	}

	private static final class Rule
	{
		final String pattern;
		final Pattern regex;

		Rule(String pattern, Pattern regex)
		{
			this.pattern = pattern;
			this.regex = regex;
		}
	}
}

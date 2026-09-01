package com.mrleonardos.codesides.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Конфиг плагина: блок {@code codeSides { ... }} в build.gradle мода.
 *
 * <pre>
 * codeSides {
 *     archiveBaseName = 'mymod'
 *     inputJar = tasks.named('reobfJar').flatMap { it.archiveFile }
 *     serverOnlyResources = ['assets/mymod/secret/**']
 * }
 * </pre>
 */
public abstract class CodeSidesExtension
{
	/** Базовое имя выходных jar: {@code <base>-<version>-server.jar}. По умолчанию имя проекта. */
	public abstract Property<String> getArchiveBaseName();

	/** Версия в имени выходных jar. По умолчанию версия проекта. */
	public abstract Property<String> getArchiveVersion();

	/**
	 * Готовый jar, который надо разрезать. Для 1.7.10 сюда даётся реобфусцированный jar, иначе стороны
	 * разъедутся с маппингами. Если не задан, режутся каталоги сборки.
	 */
	public abstract RegularFileProperty getInputJar();

	/** Ронять сборку при битой ссылке на вырезанный символ. По умолчанию включено. */
	public abstract Property<Boolean> getStrict();

	/** Собирать серверный jar. */
	public abstract Property<Boolean> getServer();

	/** Собирать клиентский jar. */
	public abstract Property<Boolean> getClient();

	/**
	 * Оставлять обычный {@code jar} со всем кодом. Он нужен для dev-запусков, но раздавать его нельзя:
	 * серверный код в нём физически присутствует.
	 */
	public abstract Property<Boolean> getUniversalJar();

	/** Проверять абстрактные контракты внешних типов (Minecraft, Forge) по compile classpath. */
	public abstract Property<Boolean> getVerifyAgainstClasspath();

	/** Добавлять маски ресурсов по умолчанию: см. {@code ResourceRules}. */
	public abstract Property<Boolean> getDefaultResourceRules();

	/** Маски ресурсов только для серверного jar. */
	public abstract ListProperty<String> getServerOnlyResources();

	/** Маски ресурсов только для клиентского jar. */
	public abstract ListProperty<String> getClientOnlyResources();
}

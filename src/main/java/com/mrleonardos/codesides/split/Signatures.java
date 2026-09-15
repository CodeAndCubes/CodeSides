package com.mrleonardos.codesides.split;

import java.util.ArrayList;
import java.util.List;

/**
 * Имена классов внутри generic-сигнатуры: общий разбор для сплиттера (чистка таблиц локальных переменных)
 * и верификатора (проверка сигнатур классов, полей, методов).
 */
public final class Signatures
{
	private Signatures()
	{
	}

	/**
	 * Internal names всех классов, упомянутых в сигнатуре.
	 *
	 * @param signature generic-сигнатура или {@code null}
	 */
	public static List<String> classNames(String signature)
	{
		List<String> names = new ArrayList<>();
		if (signature == null)
			return names;
		int i = 0;
		while ((i = signature.indexOf('L', i)) >= 0)
		{
			int end = i + 1;
			while (end < signature.length() && ";<.".indexOf(signature.charAt(end)) < 0)
				end++;
			names.add(signature.substring(i + 1, end));
			i = end;
		}
		return names;
	}
}

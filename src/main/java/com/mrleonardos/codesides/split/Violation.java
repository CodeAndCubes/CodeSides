package com.mrleonardos.codesides.split;

/** Нарушение контракта разделения: оставшийся код зависит от того, что вырезано на этой стороне. */
public final class Violation
{
	/** Ссылка из оставшегося кода на вырезанный класс. */
	public static final String KIND_CLASS = "class";
	/** Ссылка из оставшегося кода на вырезанный метод. */
	public static final String KIND_METHOD = "method";
	/** Ссылка из оставшегося кода на вырезанное поле. */
	public static final String KIND_FIELD = "field";
	/** Имя вырезанного класса, оставшееся в generic-сигнатуре (имя утекает в API и ломает компилируемость). */
	public static final String KIND_SIGNATURE = "signature";
	/** Абстрактный метод остался без реализации: вырезали то, что его реализовывало. */
	public static final String KIND_ABSTRACT = "abstract";
	/** Помечено стороной поле-константа: значение уже подставлено компилятором, вырезание поля его не убирает. */
	public static final String KIND_CONSTANT = "constant";
	/** Значение вырезанной константы физически осталось в байткоде этой стороны. */
	public static final String KIND_INLINED = "inlined";
	/** Вырезанное поле присваивается в конструкторе или статическом блоке, который остался на этой стороне. */
	public static final String KIND_INITIALIZER = "initializer";

	public final String from;
	public final String fromMember;
	public final String target;
	public final String kind;
	/** Строка исходника из LineNumberTable, ноль если место без номера (класс, поле, заголовок метода). */
	public final int line;

	public Violation(String from, String fromMember, String target, String kind)
	{
		this(from, fromMember, target, kind, 0);
	}

	public Violation(String from, String fromMember, String target, String kind, int line)
	{
		this.from = from;
		this.fromMember = fromMember;
		this.target = target;
		this.kind = kind;
		this.line = line;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
			return true;
		if (!(o instanceof Violation))
			return false;
		Violation other = (Violation) o;
		return from.equals(other.from) && target.equals(other.target) && kind.equals(other.kind) && line == other.line
			&& (fromMember == null ? other.fromMember == null : fromMember.equals(other.fromMember));
	}

	@Override
	public int hashCode()
	{
		return (((from.hashCode() * 31 + (fromMember == null ? 0 : fromMember.hashCode())) * 31 + target.hashCode())
			* 31 + kind.hashCode()) * 31 + line;
	}

	@Override
	public String toString()
	{
		String where = from.replace('/', '.') + (fromMember != null ? " (" + fromMember + place() + ")" : "");
		if (KIND_ABSTRACT.equals(kind))
			return where + " -> нет реализации абстрактного метода " + target;
		if (KIND_SIGNATURE.equals(kind))
			return where + " -> вырезанный класс " + target.replace('/', '.') + " в сигнатуре";
		if (KIND_CONSTANT.equals(kind))
			return where + " -> константа времени компиляции " + target.replace('/', '.')
				+ ": значение уже подставлено по месту использования";
		if (KIND_INLINED.equals(kind))
			return where + " -> значение вырезанной константы " + target.replace('/', '.') + " осталось в байткоде";
		if (KIND_INITIALIZER.equals(kind))
			return where + " -> вырезанное поле " + target.replace('/', '.') + " присваивается в инициализаторе";
		return where + " -> вырезанный " + kind + " " + target.replace('/', '.');
	}

	private String place()
	{
		return line > 0 ? ", строка " + line : "";
	}
}

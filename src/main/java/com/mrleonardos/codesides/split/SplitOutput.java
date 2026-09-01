package com.mrleonardos.codesides.split;

import java.util.Map;
import java.util.Set;

import com.mrleonardos.codesides.Side;

/**
 * Результат разрезания набора классов под ОДНУ сторону: что осталось и что было вырезано.
 *
 * <p>Реестр вырезанного нужен верификатору ({@link SideVerifier}), который ищет в оставшемся коде ссылки
 * на удалённые символы.
 */
public final class SplitOutput
{
	/** Сторона, под которую резали. */
	public final Side side;
	/** Оставшиеся классы: internal name → байткод. */
	public final Map<String, byte[]> classes;
	/** Классы, вырезанные целиком. */
	public final Set<String> removedClasses;
	/** Методы и конструкторы, вырезанные из оставшихся классов. */
	public final Set<MemberRef> removedMethods;
	/** Поля, вырезанные из оставшихся классов. */
	public final Set<MemberRef> removedFields;
	/**
	 * Значения вырезанных констант времени компиляции ({@code static final} примитив или String с
	 * ConstantValue). Такое значение javac подставил по месту использования, поэтому удаление поля его
	 * не убирает: верификатор ищет эти значения в оставшемся байткоде.
	 */
	public final Map<MemberRef, Object> removedConstants;

	public SplitOutput(Side side, Map<String, byte[]> classes, Set<String> removedClasses,
		Set<MemberRef> removedMethods, Set<MemberRef> removedFields, Map<MemberRef, Object> removedConstants)
	{
		this.side = side;
		this.classes = classes;
		this.removedClasses = removedClasses;
		this.removedMethods = removedMethods;
		this.removedFields = removedFields;
		this.removedConstants = removedConstants;
	}
}

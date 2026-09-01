package com.mrleonardos.codesides.split;

/**
 * Источник байткода классов, которых нет в самом моде: Minecraft, Forge, библиотеки. Верификатор
 * поднимается по ним, чтобы поймать вырезанную реализацию абстрактного метода внешнего типа
 * (например {@code IInventory}), иначе такое всплыло бы только в рантайме у игрока.
 */
public interface ClassLookup
{
	/** Ничего не знает о внешних классах: проверка ограничивается типами самого мода. */
	ClassLookup EMPTY = new ClassLookup()
	{
		@Override
		public byte[] find(String internalName)
		{
			return null;
		}
	};

	/** Байткод класса по internal name ({@code java/lang/Object}) либо {@code null}, если класс неизвестен. */
	byte[] find(String internalName);
}

package com.mrleonardos.codesides.split.fixture;

public final class Boxed implements Comparable<Boxed>
{
	public final int value;

	public Boxed(int value)
	{
		this.value = value;
	}

	@Override
	public int compareTo(Boxed other)
	{
		return Integer.compare(value, other.value);
	}
}

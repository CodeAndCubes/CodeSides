package com.mrleonardos.codesides.split.fixture;

public final class EnumUser
{
	public int count()
	{
		return Flavours.values().length;
	}

	public String pick(Flavours flavour)
	{
		switch (flavour)
		{
			case SWEET:
				return "sweet";
			default:
				return "sour";
		}
	}
}

package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.ServerSide;

public final class NamedInsideAnonymous
{
	@ServerSide
	public Api anonymousWithNamedMember()
	{
		return new Api()
		{
			final class Helper
			{
				String value()
				{
					return "codesides-named-inside-secret";
				}
			}

			@Override
			public String describe()
			{
				return new Helper().value();
			}
		};
	}
}

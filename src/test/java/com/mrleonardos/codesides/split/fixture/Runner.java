package com.mrleonardos.codesides.split.fixture;

import com.mrleonardos.codesides.split.fixture.commonpkg.CommonThing;

public final class Runner
{
	public static String run()
	{
		GoodCommon good = new GoodCommon(() -> "api-ok");
		EnumUser enums = new EnumUser();
		return good.describe() + "|" + new Mixed().common() + "|" + enums.count() + "|"
			+ enums.pick(Flavours.SOUR) + "|" + new Lambdas().common() + "|" + new Boxed(1).compareTo(new Boxed(2))
			+ "|" + new CommonThing().name();
	}
}

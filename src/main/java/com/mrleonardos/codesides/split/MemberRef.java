package com.mrleonardos.codesides.split;

/**
 * Ссылка на член класса (метод или поле) в формате {@code owner.name desc},
 * где owner это internal name владельца.
 */
public final class MemberRef
{
	public final String owner;
	public final String name;
	public final String desc;

	public MemberRef(String owner, String name, String desc)
	{
		this.owner = owner;
		this.name = name;
		this.desc = desc;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
			return true;
		if (!(o instanceof MemberRef))
			return false;
		MemberRef m = (MemberRef) o;
		return owner.equals(m.owner) && name.equals(m.name) && desc.equals(m.desc);
	}

	@Override
	public int hashCode()
	{
		return (owner.hashCode() * 31 + name.hashCode()) * 31 + desc.hashCode();
	}

	@Override
	public String toString()
	{
		return owner + "." + name + " " + desc;
	}
}

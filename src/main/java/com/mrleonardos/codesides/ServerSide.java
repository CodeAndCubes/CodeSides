package com.mrleonardos.codesides;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Помеченный элемент существует ТОЛЬКО в серверном jar и физически вырезается из клиентского на сборке.
 * Применяется точечно: метить нужно лишь то, что обязано жить на одной стороне; непомеченное считается
 * общим (common) и попадает в оба jar.
 *
 * <p>Куда можно ставить:
 * <ul>
 *   <li>{@link ElementType#TYPE}: класс целиком (вместе с вложенными, анонимными и локальными классами);</li>
 *   <li>{@link ElementType#METHOD} / {@link ElementType#CONSTRUCTOR}: метод или конструктор, вместе с его
 *       лямбдами и анонимными классами;</li>
 *   <li>{@link ElementType#FIELD}: поле;</li>
 *   <li>{@link ElementType#PACKAGE}: в {@code package-info.java}, тогда весь пакет и все его подпакеты.</li>
 * </ul>
 *
 * <p><b>Контракт (проверяется верификатором, строгий режим):</b> общий код НЕ должен ссылаться на
 * server-only символ, иначе клиентский jar получил бы битую ссылку. Приём: серверную логику прятать за
 * общим интерфейсом, а реализацию помечать этой аннотацией.
 *
 * <p>Retention {@link RetentionPolicy#CLASS}: аннотация видна сплиттеру в байткоде, в рантайм не грузится
 * и стирается из выходных классов, так что готовый мод не зависит от CodeSides.
 *
 * @see ClientSide
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD, ElementType.PACKAGE })
public @interface ServerSide
{
}

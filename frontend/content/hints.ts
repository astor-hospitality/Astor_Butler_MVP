/**
 * Clio as a guide: the short notes she shows next to her star while the visitor reads the page.
 * Everything about them is here: the rules and the words. Edit the words freely; one or two short sentences each.
 *
 * A note belongs to a part of a page (`target`, a CSS selector) and is shown when that part fills the screen.
 * A click on the note opens the chat, and Clio starts it with `opener`, so the talk goes on from the same subject.
 * The same selector may be found on several pages (`#price` is on every direction's page): the note is then shown on
 * the first of them the visitor reaches, once. The notes about the seven directions belong to their own pages
 * (the first screen of a direction's page carries its key as an id: `#ai`, `#events` and so on). The row of directions
 * on the home page carries no such ids, so nothing pops up over its pictures: the row is kept as bare as it can be.
 */
export interface Hint {
  /** Its name in the browser's memory of what was already shown. Do not reuse the name of a note that was taken away. */
  id: string;
  target: string;
  text: string;
  opener: string;
}

export const hintRules = {
  /** The share of the screen the part has to fill, or the share of the part that has to be on screen (whichever comes first). */
  inView: 0.5,
  /** Seconds the part has to stay there before the note appears: a visitor scrolling past is not interrupted. */
  dwell: 1.2,
  /** Seconds a note stays if nobody touches it. */
  stay: 5.5,
  /** Seconds between two notes, at least. */
  gap: 15,
  /** Seconds after the red screen has let go of the page before the first note may appear. */
  afterIntro: 6,
  /** After this many notes closed by the visitor in a row she stays silent until the tab is closed. */
  quietAfter: 2,
};

export const hints: Hint[] = [
  {
    id: "ai",
    target: "#ai",
    text: "Ролик без съёмочной площадки. Рассказать, как это делается и сколько занимает?",
    opener: "ИИ-ролик собираем без съёмки: идея, раскадровка, кадры, монтаж. Расскажите, что рекламируем, и я передам задачу команде.",
  },
  {
    id: "events",
    target: "#events",
    text: "Снимем ваше событие и отдадим ролик через 3–5 рабочих дней. Подсказать пакет?",
    opener: "Для репортажа мне нужны дата, город и сколько часов длится событие. По ним подскажу пакет.",
  },
  {
    id: "reels",
    target: "#reels",
    text: "Десять роликов за одну смену. Хотите, прикину под ваше заведение?",
    opener: "Расскажите, что у вас за заведение и где оно. Скажу, как пройдёт смена и что вы получите.",
  },
  {
    id: "wedding",
    target: "#wedding",
    text: "Свадебный фильм, тизер и короткие ролики. Спросите меня про даты и пакеты.",
    opener: "Назовите дату и город свадьбы. Подскажу пакет и узнаю, свободна ли команда.",
  },
  {
    id: "podcast",
    target: "#podcast",
    text: "Запишем подкаст под ключ. Могу рассказать, что понадобится от вас.",
    opener: "Для подкаста нужны тема, число гостей и сколько выпусков вы задумали. С этого и начнём.",
  },
  {
    id: "reclama",
    target: "#reclama",
    text: "Рекламный ролик считаем под задачу. Опишите её в двух словах, я передам команде.",
    opener: "Что рекламируем и где ролик будут смотреть? Этого хватит, чтобы команда вернулась со сметой.",
  },
  {
    id: "film",
    target: "#film",
    text: "Фильм о компании или о человеке. Расскажите, о ком он, и я подскажу, с чего начать.",
    opener: "О ком или о чём фильм и к какой дате он нужен? Передам команде, они назначат встречу.",
  },
  {
    id: "work",
    target: "#catalog",
    text: "Плюс на карточке сохраняет работу как пример. Соберите несколько, и я передам их вместе с задачей.",
    opener: "Отметьте плюсом работы, которые вам близки, и напишите, что нужно снять. Передам всё вместе.",
  },
  {
    id: "shoot",
    target: "#shoot",
    text: "Три шага: что снимаем, пакет и примеры. Соберёте, и я передам всё команде.",
    opener: "Соберите раскадровку и нажмите «Отправить Clio». Или просто напишите задачу здесь, своими словами.",
  },
  {
    id: "about",
    target: "#about",
    text: "Это команда C3. А я на связи всегда, если нужно что-то спросить.",
    opener: "С командой познакомились. Теперь расскажите о вашей задаче: что снимаем и к какому сроку?",
  },
  {
    id: "contact",
    target: "#contact",
    text: "Можно написать и мне. Передам задачу команде, они ответят.",
    opener: "Напишите, что нужно снять, и оставьте, как с вами связаться. Передам команде.",
  },
  {
    id: "price",
    target: "#price",
    text: "Не знаете, что выбрать? Скажите, что снимаем, и я подскажу.",
    opener: "Расскажите в двух словах, что нужно снять. Подскажу, что подойдёт и что в это входит.",
  },
  {
    id: "details",
    target: "#details",
    text: "Если чего-то нет в списке, спросите меня: скажу, входит ли это.",
    opener: "Что хотите уточнить по составу или срокам? Отвечу или узнаю у команды.",
  },
];

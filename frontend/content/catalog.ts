import { mediaImage } from "@/lib/media";
import type { DirectionKey, Picture } from "./types";

/**
 * The works, direction by direction: a row of frames for each. Since 2026-10-25 most of them come from the studio's
 * portfolio on Google Drive (see tools/drive/inventory.md), in the order the studio numbered them there; the rest are
 * still in its Yandex Disk folder. The names are read off the file names. A frame is a still of the film,
 * 1280 px on the long side where the source allowed it. The frames live with the rest of the media (images/work under the
 * media root, see src/lib/media.ts); their sizes come from content/media-manifest.ts.
 */
export interface Work {
  title: string;
  frame: Picture;
  /** The name of the frame's file: the work's full film (and its short preview, if any) is found by it (see work-media.ts). */
  file: string;
  /** A work that has its own page (see cases.ts). */
  slug?: string;
}

const w = (file: string, name: string, title: string, alt: string, slug?: string): Work => ({
  title,
  file: name,
  frame: { ...mediaImage(file), alt },
  ...(slug ? { slug } : {}),
});

/**
 * How many films the last card of the works rail counts: it says how many more there are than the site shows.
 * Counted 2026-10-25: 79 films in the six Drive folders the studio has released (AI 10, «Коммерческие ролики» 19
 * without the folder «шмотки», «Клипы» 7, «Реклама» 10, «Эвент и репортажи» 21, «Свадьбы» 12), and the 16 films
 * on the site that are not in them (vertical videos, podcasts, film, VIKING, AERIS). The folders the studio is still
 * sorting are not counted, so the card says less than the archive holds, never more.
 */
export const ARCHIVE_TOTAL = 95;

export const catalog: Record<DirectionKey, Work[]> = {
  // vertical videos, podcasts and film are still on the Disk: their Drive folders are not released yet
  reels: [
    w("images/work/dir-reels.jpg", "dir-reels", "Paralleli – стейк рибай", "Стейк на гриле, его поднимают щипцами"),
    w("images/work/paralleli-1.jpg", "paralleli-1", "Paralleli – коктейль", "Бармен переливает коктейль в шейкер", "paralleli"),
    w("images/work/w-119.jpg", "w-119", "Сойка – сибас", "Соус льётся из соусника в тарелку"),
    w("images/work/w-091.jpg", "w-091", "AERIS Gastrobar", "Гитарист в очках играет в красном свете"),
    w("images/work/w-145.jpg", "w-145", "Alfea – Mercedes", "Рука на эмблеме Mercedes"),
    w("images/work/w-112.jpg", "w-112", "Маяковка", "Мужчина в пиджаке сидит в кресле"),
  ],
  // the Drive folder «4. Эвент и репортажи» in the order of its numbers, BASTA's three parts as three works
  events: [
    w("images/work/ev-1.jpg", "ev-1", "VK – Блогеры России", "Певица в блестящем костюме поёт на сцене", "bloggers"),
    w("images/work/ev-2.jpg", "ev-2", "Яндекс Маркет – «Маркета»", "Певица с микрофоном смеётся на сцене"),
    w("images/work/ev-3a.jpg", "ev-3a", "BASTA – итоговый ролик, часть 1", "Мужчина в кепке поправляет наушники в красном свете"),
    w("images/work/ev-3b.jpg", "ev-3b", "BASTA – итоговый ролик, часть 2", "Сцена в розовом свете перед полным залом"),
    w("images/work/ev-3c.jpg", "ev-3c", "BASTA – итоговый ролик, часть 3", "Зрители у сцены тянут руки вверх"),
    w("images/work/ev-4.jpg", "ev-4", "Театр войны – 9 Мая", "Актёры в военной форме сороковых годов в синем свете"),
    w("images/work/ev-5.jpg", "ev-5", "Groovemode Session", "Девушка танцует в розовом свете"),
    w("images/work/ev-6.jpg", "ev-6", "5 Years After", "Толпа перед сценой в оранжевом свете"),
    w("images/work/ev-7.jpg", "ev-7", "BRUSS – день рождения", "Ведущий с микрофоном среди зрителей"),
    w("images/work/ev-8.jpg", "ev-8", "DXB – итоговый ролик", "Диджей за пультом под открытым небом, вокруг гости"),
    w("images/work/ev-9.jpg", "ev-9", "White Night", "Диджей у пульта перед танцующим залом"),
    w("images/work/ev-10.jpg", "ev-10", "Визит министра транспорта", "Кузов поезда на подъёмниках в заводском цеху"),
    w("images/work/ev-11.jpg", "ev-11", "WeAre – показ мод", "Модель в белом на подиуме"),
    w("images/work/ev-12.jpg", "ev-12", "Fabrica – итоговый ролик", "Зал с поднятыми руками в свете прожекторов"),
    w("images/work/ev-13.jpg", "ev-13", "Kupala – итоговый ролик", "Девушки с ветками зелени танцуют на поляне"),
    w("images/work/ev-14.jpg", "ev-14", "WeAre – Imen, итоговый ролик", "Толпа в дыму перед сценой"),
    w("images/work/ev-15.jpg", "ev-15", "Yarmarka – итоговый ролик", "Две певицы в народных костюмах поют на сцене"),
    w("images/work/ev-16.jpg", "ev-16", "Extreme Fest", "Два воздушных шара над лесом"),
    w("images/work/ev-17.jpg", "ev-17", "Концерт – полная запись", "Басист и певица на сцене в синем свете"),
    w("images/work/ev-18.jpg", "ev-18", "Эрмитаж – мастер-класс", "Женщина в кружевной блузе у кирпичной стены"),
  ],
  // the Drive folder «5. Свадьбы» in the order of its numbers
  wedding: [
    w("images/work/wed-1.jpg", "wed-1", "Судьба", "Жених и невеста за длинным праздничным столом"),
    w("images/work/wed-2.jpg", "wed-2", "Просто тебя люблю", "Жених и невеста показывают кольца"),
    w("images/work/wed-3.jpg", "wed-3", "Метафора", "Пара целуется под белой цветочной аркой у воды"),
    w("images/work/wed-4.jpg", "wed-4", "Записки о любви", "Жених пишет письмо за деревянным столом"),
    w("images/work/wed-5.jpg", "wed-5", "Случайная любовь случайна навсегда", "Жених держит невесту на руках у мотороллера"),
    w("images/work/wed-6.jpg", "wed-6", "WeДДing ЙоGа", "Пара в дверях под гирляндой красно-белых флажков"),
    w("images/work/wed-7.jpg", "wed-7", "Небанальная история", "Розы в темноте"),
    w("images/work/wed-8.jpg", "wed-8", "Купаться запрещено", "Невеста в белом платье сидит на кровати"),
    w("images/work/wed-9.jpg", "wed-9", "Видеограф или шампанское", "Невеста надевает серьгу"),
    w("images/work/wed-10.jpg", "wed-10", "Спиной на самокате", "Жених и невеста обнимаются у моста"),
    w("images/work/wed-11.jpg", "wed-11", "Любовь растопит лёд", "Лицо девушки среди белых цветов"),
    w("images/work/wed-12.jpg", "wed-12", "Видеосъёмка свадебного дня", "Девушка лежит среди ромашек"),
  ],
  podcast: [
    w("images/work/dir-podcast.jpg", "dir-podcast", "Подкаст «Бесполезные»", "Трое ведущих за столом с микрофонами"),
    w("images/work/w-158.jpg", "w-158", "Подкаст УГМК – трейлер", "Гость в очках смеётся у микрофона"),
    w("images/work/w-157.jpg", "w-157", "Подкаст «Гринвич» – трейлер", "Заставка подкаста с портретом гостьи"),
    w("images/work/w-155.jpg", "w-155", "WeAre – Moscow Travel, интервью", "Мужчина в тёмных очках на переднем сиденье машины"),
  ],
  // the Drive folder «3. Реклама» first, then «0. Коммерческие ролики» (its numbered films only), both in the order
  // of their numbers (Sergey, 2026-10-27: the advertising row was in the wrong order); AERIS, from the Disk, keeps its own page
  reclama: [
    w("images/work/rec-1.jpg", "rec-1", "Русский язык", "Женщина в красном платье и жемчуге за столом при свечах"),
    w("images/work/rec-2.jpg", "rec-2", "Поджоги", "Люди в камуфляже в дверном проёме"),
    w("images/work/rec-3.jpg", "rec-3", "Анатолий Мовлян – онлайн-школа", "Кисть кладёт мазок на портрет маслом"),
    w("images/work/rec-4.jpg", "rec-4", "Гипербола – ролик 1", "Покупательница с тележкой у прилавка с фруктами"),
    w("images/work/rec-4-1.jpg", "rec-4-1", "Гипербола – ролик 2", "Подарочная корзина с сыром, мёдом и фруктами"),
    w("images/work/rec-6.jpg", "rec-6", "Overlust – Vox School", "Люди в масках перед экранами в тёмной студии"),
    w("images/work/rec-7.jpg", "rec-7", "Zombollector – ролик для ТВ", "Мужчина в белом халате с зелёной чашкой у голубого дома"),
    w("images/work/rec-8.jpg", "rec-8", "Музей ИЗО Екатеринбурга", "Зал музея с картинами на серых стенах"),
    w("images/work/rec-9.jpg", "rec-9", "Школа живописи Мовляна", "Портреты маслом и рисунки на стене мастерской"),
    w("images/work/com-0.jpg", "com-0", "WeAre – Stuff", "Руки раскладывают белую, красную и чёрную футболки"),
    w("images/work/com-1.jpg", "com-1", "Набат", "Трое в форме охраны склонились над столом"),
    w("images/work/com-2.jpg", "com-2", "Контрацепция", "Мужчина в белом халате и очках в зелёном свете, рядом микроскоп"),
    w("images/work/com-3.jpg", "com-3", "НЛМК – «Мы любим то, что делаем»", "Рабочий в синей каске в цеху завода"),
    w("images/work/com-4.jpg", "com-4", "MSK Leader – ролик 1", "Трубы с красными вентилями и насосы в техническом помещении"),
    w("images/work/com-4-1.jpg", "com-4-1", "MSK Leader – ролик 2", "Открытый шкаф управления с автоматикой и рядами кнопок"),
    w("images/work/aeris-1.jpg", "aeris-1", "AERIS – анимированные афиши", "Афиша AERIS на красном фоне: 8 ноября", "aeris"),
  ],
  film: [
    w("images/work/dir-film.jpg", "dir-film", "«Электросетьстрой 11»", "Герой фильма даёт интервью в полутёмном кабинете", "ess-11"),
    w("images/work/w-044.jpg", "w-044", "WA – 2 Years, промо", "Девушка в панаме под деревом"),
    w("images/work/w-045.jpg", "w-045", "We Are Wear – промо", "Руки над выкройкой на столе"),
    w("images/work/w-046.jpg", "w-046", "Pink Panther – видеокурс", "Мужчина в кепке и тёмных очках на сером фоне"),
  ],
  // the Drive folder «AI» in the order of its numbers; VIKING, from the Disk, keeps its own page
  ai: [
    w("images/work/ai-1.jpg", "ai-1", "BAZA – ALISA, шоурил", "Фасад дома с фиолетовой подсветкой балконов на закате"),
    w("images/work/ai-2.jpg", "ai-2", "BAZA – «Бестселлер»", "Раскрытый журнал с новым жилым кварталом на развороте"),
    w("images/work/ai-3.jpg", "ai-3", "Tangiers", "Рука на странице книги со словами Tangiers Lounge"),
    w("images/work/ai-4.jpg", "ai-4", "Morgan Barbie", "Куклы в стиле Барби с бокалами на вечеринке"),
    w("images/work/viking-1.jpg", "viking-1", "VIKING", "Девушка в тёмных очках в красном свете", "viking"),
  ],
};

/**
 * The studio's music videos (the Drive folder «2. Клипы», in the order of its numbers). They are not one of the seven
 * directions and have no page: the works rail shows them as a group of their own, with a chip of their own.
 */
export const clips: Work[] = [
  w("images/work/clip-1.jpg", "clip-1", "Комбат", "Машина на заснеженной дороге и название клипа «Комбат»"),
  w("images/work/clip-2.jpg", "clip-2", "Poet's Soul", "Дом ночью и название клипа Poet's Soul"),
  w("images/work/clip-3.jpg", "clip-3", "E-City Life", "Двое на поле пустого стадиона"),
  w("images/work/clip-4.jpg", "clip-4", "От Волги до Енисея", "Человек обнимает берёзу, поверх название клипа"),
  w("images/work/clip-5.jpg", "clip-5", "Return of the Soul – Disconnect", "Гитарист в полутьме, на лице красный отсвет"),
  w("images/work/clip-6.jpg", "clip-6", "Dark Side of Silence – Vengeance", "Мужчина в капюшоне в полутёмном коридоре"),
  w("images/work/clip-7.jpg", "clip-7", "Para Reportir", "Мужчина в шляпе и девушка в танце"),
];

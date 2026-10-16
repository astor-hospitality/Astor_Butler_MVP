/**
 * Made by tools/film/work_media.py: do not edit by hand, run the script again.
 * For each work of the catalogue (by the name of its frame file): `clip` is its short silent preview under the media root (S3, see src/lib/media.ts),
 * if one was cut; `source` is its film in the studio's public Disk folder, played in full by the player
 * through /api/watch (the address Yandex gives lives for a short while, so it is asked for at the moment of playing).
 */
export interface WorkMedia {
  clip?: string;
  source: string;
}

export const workMedia: Record<string, WorkMedia> = {
  "aeris-1": { source: "/VIDEO C3AG/Моушен графика/AFISHA/AERIS AFISHA.mp4" },
  "bloggers-2": { clip: "/videos/work/bloggers-2.mp4", source: "/VIDEO C3AG/4. Эвент и репортажи/4.VK Блогеры России.mp4" },
  "dir-ai": { clip: "/videos/work/dir-ai.mp4", source: "/AI/TANGIERS.mp4" },
  "dir-events": { source: "/VIDEO C3AG/4. Эвент и репортажи/WEARE MOSCOW TRAVEL 2.mov" },
  "dir-film": { source: "/VIDEO C3AG/1.Докментально кино/«Электросетьстрой 11»  Фильм/ESS11 FILM 2K SCOPE 2.39 100MB GRAIN.mp4" },
  "dir-podcast": { source: "/VIDEO C3AG/7. Подкасты/подкаст бесполезные.mov" },
  "dir-reclama": { clip: "/videos/work/dir-reclama.mp4", source: "/VIDEO C3AG/3. Реклама/museum - pink bloody paloma (master).mp4" },
  "dir-reels": { clip: "/videos/work/dir-reels.mp4", source: "/VIDEO C3AG/5. Reels Контент/REELS/paralleli/стейк рибай.mp4" },
  "paralleli-1": { source: "/VIDEO C3AG/5. Reels Контент/REELS/paralleli/коктель 2.mp4" },
  "viking-1": { source: "/AI/Афиша для VIKING/1.mp4" },
  "w-013": { clip: "/videos/work/w-013.mp4", source: "/AI/Афиша KIIDA/9ac8a5369d7a18577018e44eb0d7f6fc_7a4256e1-5363-4f42-82be-fe89dc1f4c65_2_prob4.mp4" },
  "w-015": { clip: "/videos/work/w-015.mp4", source: "/AI/Афиша PHILCHANSKY/5.mp4" },
  "w-019": { source: "/AI/Афиша SENLIMA/2_2_prob4.mp4" },
  "w-028": { source: "/AI/Афиши MONTA/10.mp4" },
  "w-044": { source: "/VIDEO C3AG/1.Докментально кино/WA 2 YEARS PROMO.mp4" },
  "w-045": { source: "/VIDEO C3AG/1.Докментально кино/WE ARE WEAR PROMO.mov" },
  "w-046": { source: "/VIDEO C3AG/1.Докментально кино/pink panther - video course (main feed).mp4" },
  "w-069": { source: "/VIDEO C3AG/3. Реклама/Eyebrows _ beauty saloon.mp4" },
  "w-071": { source: "/VIDEO C3AG/3. Реклама/Zombolleсtor-s-TV-Commercial.mp4" },
  "w-073": { source: "/VIDEO C3AG/3. Реклама/overlust - vox school (master).mp4" },
  "w-076": { source: "/VIDEO C3AG/3. Реклама/крым отель MORE.mov" },
  "w-078": { source: "/VIDEO C3AG/4. Эвент и репортажи/2. BASTA Aftermovie part 2.mov" },
  "w-081": { source: "/VIDEO C3AG/4. Эвент и репортажи/EXTREME FEST.mov" },
  "w-083": { source: "/VIDEO C3AG/4. Эвент и репортажи/WEARE IMEN AFTERMOVIE.mov" },
  "w-087": { clip: "/videos/work/w-087.mp4", source: "/VIDEO C3AG/4. Эвент и репортажи/grovemode session full.mp4" },
  "w-091": { source: "/VIDEO C3AG/5. Reels Контент/REELS/AERIS/AERIS GASTROBAR.mov" },
  "w-112": { clip: "/videos/work/w-112.mp4", source: "/VIDEO C3AG/5. Reels Контент/REELS/Маяковка/рилсы 2 1.mp4" },
  "w-119": { clip: "/videos/work/w-119.mp4", source: "/VIDEO C3AG/5. Reels Контент/REELS/Сойка/сибас.mov" },
  "w-145": { clip: "/videos/work/w-145.mp4", source: "/VIDEO C3AG/5. Reels Контент/REELS/ALFEA MERCEDES.mov" },
  "w-155": { source: "/VIDEO C3AG/6. Интервью/WEARE MOSCOW TRAVEL 1.mov" },
  "w-157": { source: "/VIDEO C3AG/7. Подкасты/Подкаст трейлер Гринвич.mp4" },
  "w-158": { source: "/VIDEO C3AG/7. Подкасты/Подкаст трейлер УГМК.mov" },
};

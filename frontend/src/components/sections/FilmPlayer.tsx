"use client";

import { useCallback, useEffect, useRef, useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from "react";
import type Hls from "hls.js";
import { ChromeIcon } from "../ChromeIcon";

interface FilmPlayerProps {
  /** The full address of the work's stream, `.../<key>/master.m3u8`. */
  src: string;
  /** The work's frame: shown until the first frame of the film. */
  poster: string;
  /** Its shape (width over height) until the film itself says: the box is drawn in it from the start. */
  shape: number;
  title: string;
  /** The stream did not play (no network, no manifest, a file the browser cannot read). */
  onFail: () => void;
}

/** One of the stream's qualities as the menu offers it. */
interface Quality {
  /** The index of the rendition in hls.js. */
  level: number;
  /** «1080p», «720p», «480p». */
  label: string;
}

const MPEGURL = "application/vnd.apple.mpegurl";
/** How long the controls stay after the last move of the pointer while the film plays. */
const IDLE_MS = 2600;
/** How far ← and → move the film. */
const STEP = 5;

/**
 * The name of a rendition as people know it, from its picture: a 1920 wide film is «1080p» whatever its height
 * (a scope film of 1920×804 is still the 1080 one), and a vertical one is named by its width. The top rung of a 4K
 * master is called «4K», as people say it (Roma, 2026-10-27: 4K films get a 4K rendition too).
 */
function qualityName(width: number, height: number): string {
  const short = Math.min(width, height);
  const long = Math.max(width, height);
  const lines = Math.max(short, Math.round((long * 9) / 16));
  const named = [2160, 1440, 1080, 720, 480, 360, 240].find((n) => lines >= n * 0.9) ?? lines;
  return named === 2160 ? "4K" : `${named}p`;
}

/** 75 → «1:15», 3725 → «1:02:05». */
function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const two = (n: number) => String(n).padStart(2, "0");
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${h}:${two(m)}:${two(s % 60)}` : `${m}:${two(s % 60)}`;
}

/** iPhone Safari cannot put a box in full screen, only the video itself, in its own player. */
type AppleVideo = HTMLVideoElement & { webkitEnterFullscreen?: () => void };

/**
 * The site's own player for a work's film, streamed as HLS in three qualities (the client, 2026-10-27: «Плеер тупит,
 * не дает выбрать качество, глючит» about Drive's player). Safari and the iPhone play the stream by themselves; every
 * other browser plays it through hls.js, which is loaded only now, when a film is opened, and gives the quality menu.
 * It starts with sound (a click opened it); a browser that still refuses sound gets the film muted, and the sound
 * button says so. The controls fade while the film plays and come back with the pointer, a tap or a key.
 * Keys: space or K plays and pauses, ← and → move the film by five seconds, F is the full screen, M the sound
 * (the lightbox moves to the neighbouring work with Shift and the arrows, see VideoLightbox).
 * There is no download anywhere: no menu on the film, nothing in the browser's own list.
 */
export function FilmPlayer({ src, poster, shape: frameShape, title, onFail }: FilmPlayerProps) {
  const frame = useRef<HTMLDivElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const video = useRef<HTMLVideoElement>(null);
  const hls = useRef<Hls | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const idleTimer = useRef(0);
  const failRef = useRef(onFail);
  useEffect(() => {
    failRef.current = onFail;
  }, [onFail]);

  const [shape, setShape] = useState(frameShape);
  const [playing, setPlaying] = useState(false);
  // the browser would not start the film with sound: it plays muted, and a line over it offers the sound
  const [refused, setRefused] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState(0);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [qualities, setQualities] = useState<Quality[]>([]);
  // the chosen rendition, -1 for «Авто», and the one that plays now
  const [chosen, setChosen] = useState(-1);
  const [current, setCurrent] = useState(-1);
  const [menu, setMenu] = useState(false);
  const [idle, setIdle] = useState(false);
  const [full, setFull] = useState(false);
  const [scrubbing, setScrubbing] = useState(false);
  // where the pointer stands over the scrub bar, 0..1, for the time shown above it
  const [hover, setHover] = useState<number | null>(null);
  // on a phone a wide film is a low strip, and controls over it would hide most of the picture: when the sheet
  // has room under the film, they stand there instead
  const [under, setUnder] = useState(false);
  useEffect(() => {
    const outer = frame.current;
    const inner = box.current;
    if (!outer || !inner) return;
    const read = () => setUnder(outer.clientWidth < 640 && (outer.clientHeight - inner.clientHeight) / 2 >= 84);
    const watch = new ResizeObserver(read);
    watch.observe(outer);
    watch.observe(inner);
    return () => watch.disconnect();
  }, []);

  // start: with sound if the browser lets it, muted if not
  const start = useCallback(() => {
    const el = video.current;
    if (!el) return;
    el.play().catch((error: unknown) => {
      if (error instanceof DOMException && error.name === "NotAllowedError") {
        el.muted = true;
        setRefused(true);
        el.play().catch(() => {});
      }
    });
  }, []);

  // the stream: by the browser itself where it can (Safari, the iPhone), through hls.js everywhere else.
  // Chrome on Android also says it can, but there hls.js plays better and keeps the quality menu.
  useEffect(() => {
    const el = video.current;
    if (!el) return;
    let gone = false;
    const native = el.canPlayType(MPEGURL) !== "" && (typeof window.MediaSource === "undefined" || /Apple/.test(navigator.vendor));
    if (native) {
      el.src = src;
      start();
      return () => {
        gone = true;
        el.removeAttribute("src");
        el.load();
      };
    }
    import("hls.js")
      .then(({ default: HlsJs }) => {
        if (gone) return;
        if (!HlsJs.isSupported()) {
          if (el.canPlayType(MPEGURL) !== "") {
            el.src = src;
            start();
          } else failRef.current();
          return;
        }
        const player = new HlsJs({ capLevelToPlayerSize: true, startLevel: -1 });
        hls.current = player;
        let recovered = false;
        player.on(HlsJs.Events.MANIFEST_PARSED, (_, data) => {
          // one entry per name, the richest rendition of it, the best first
          const byName = new Map<string, Quality & { bitrate: number; height: number }>();
          data.levels.forEach((level, i) => {
            const label = qualityName(level.width, level.height);
            const known = byName.get(label);
            if (!known || level.bitrate > known.bitrate) byName.set(label, { level: i, label, bitrate: level.bitrate, height: level.height });
          });
          setQualities([...byName.values()].sort((a, b) => b.height - a.height).map(({ level, label }) => ({ level, label })));
          start();
        });
        player.on(HlsJs.Events.LEVEL_SWITCHED, (_, data) => setCurrent(data.level));
        player.on(HlsJs.Events.ERROR, (_, data) => {
          if (!data.fatal) return;
          // a broken piece of picture is tried once more; a stream that does not come (network, manifest) is given up
          if (data.type === HlsJs.ErrorTypes.MEDIA_ERROR && !recovered) {
            recovered = true;
            player.recoverMediaError();
            return;
          }
          failRef.current();
        });
        player.loadSource(src);
        player.attachMedia(el);
      })
      .catch(() => {
        if (!gone) failRef.current();
      });
    return () => {
      gone = true;
      hls.current?.destroy();
      hls.current = null;
    };
  }, [src, start]);

  const toggle = useCallback(() => {
    const el = video.current;
    if (!el) return;
    if (el.paused || el.ended) start();
    else el.pause();
  }, [start]);

  const seek = useCallback((to: number) => {
    const el = video.current;
    if (!el || !Number.isFinite(el.duration)) return;
    el.currentTime = Math.min(Math.max(0, to), el.duration);
    setTime(el.currentTime);
  }, []);

  const toggleMute = useCallback(() => {
    const el = video.current;
    if (!el) return;
    el.muted = !el.muted;
    if (!el.muted && el.volume === 0) el.volume = 0.6;
  }, []);

  const toggleFull = useCallback(() => {
    const el = box.current;
    const film = video.current as AppleVideo | null;
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
    } else if (el?.requestFullscreen) {
      el.requestFullscreen().catch(() => {});
    } else {
      film?.webkitEnterFullscreen?.();
    }
  }, []);

  const pick = (level: number) => {
    setChosen(level);
    setMenu(false);
    // at once (what is already loaded in the old quality is thrown away), so the choice is seen, not promised
    if (hls.current) hls.current.currentLevel = level;
  };

  // the controls show on any sign of life and fade again after a while, unless the film stands or a menu is open
  const wake = useCallback(() => {
    setIdle(false);
    window.clearTimeout(idleTimer.current);
    idleTimer.current = window.setTimeout(() => setIdle(true), IDLE_MS);
  }, []);
  useEffect(() => () => window.clearTimeout(idleTimer.current), []);
  const hidden = idle && playing && !menu && !scrubbing;

  useEffect(() => {
    const onFull = () => setFull(document.fullscreenElement === box.current);
    document.addEventListener("fullscreenchange", onFull);
    return () => document.removeEventListener("fullscreenchange", onFull);
  }, []);

  // the menu closes by a click anywhere else
  useEffect(() => {
    if (!menu) return;
    const away = (e: PointerEvent) => {
      if (!menuRef.current?.contains(e.target as Node)) setMenu(false);
    };
    window.addEventListener("pointerdown", away);
    return () => window.removeEventListener("pointerdown", away);
  }, [menu]);

  // the keys; heard before the lightbox's own (capture), so Escape closes an open menu and not the whole player
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key === "Escape" && menu) {
        e.stopPropagation();
        setMenu(false);
        return;
      }
      // the volume slider keeps its own arrows, a menu item its own space and Enter
      if (e.target instanceof Element && e.target.closest("input, [role=menu]")) return;
      const el = video.current;
      if (!el) return;
      const key = e.key.toLowerCase();
      if (key === " " || key === "k") {
        e.preventDefault();
        toggle();
      } else if ((key === "arrowleft" || key === "arrowright") && !e.shiftKey) {
        e.preventDefault();
        seek(el.currentTime + (key === "arrowright" ? STEP : -STEP));
      } else if (key === "f" || key === "а") {
        toggleFull();
      } else if (key === "m" || key === "ь") {
        toggleMute();
      } else return;
      wake();
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [menu, toggle, seek, toggleFull, toggleMute, wake]);

  const readBuffered = () => {
    const el = video.current;
    if (!el) return;
    const ranges = el.buffered;
    let end = 0;
    for (let i = 0; i < ranges.length; i++) if (ranges.start(i) <= el.currentTime + 0.5) end = Math.max(end, ranges.end(i));
    setBuffered(end);
  };

  // the scrub bar: pressed, the film goes where the pointer is and follows it until the pointer is let go
  const fraction = (e: ReactPointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    return Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
  };
  const scrubDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!duration) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setScrubbing(true);
    seek(fraction(e) * duration);
  };
  const scrubMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const at = fraction(e);
    setHover(at);
    if (scrubbing) seek(at * duration);
  };
  const scrubUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
    setScrubbing(false);
    if (e.pointerType !== "mouse") setHover(null);
  };

  // a click on the picture plays and pauses with a mouse; a tap first brings the controls back
  const pictureTap = (e: ReactPointerEvent<HTMLVideoElement>) => {
    if (e.pointerType === "mouse") toggle();
    else if (hidden) wake();
    else toggle();
  };

  const nowLabel = qualities.find((q) => q.level === current)?.label;
  const chosenLabel = chosen === -1 ? "Авто" : (qualities.find((q) => q.level === chosen)?.label ?? "Авто");
  const quiet = muted || volume === 0;
  const part = (n: number) => (duration > 0 ? Math.min(1, n / duration) : 0);

  return (
    <div ref={frame} className="film" style={{ "--shape": shape } as CSSProperties}>
      <div
        ref={box}
        className="film-box"
        data-hidden={hidden || undefined}
        data-under={(under && !full) || undefined}
        data-full={full || undefined}
        onPointerMove={(e) => {
          if (e.pointerType === "mouse") wake();
        }}
        // a tap on the picture itself is read on its own (pictureTap): the first one only brings the controls back
        onPointerDown={(e) => {
          if (e.target !== video.current) wake();
        }}
        onContextMenu={(e) => e.preventDefault()}
      >
        <video
          ref={video}
          poster={poster}
          playsInline
          preload="auto"
          controlsList="nodownload noplaybackrate"
          disableRemotePlayback
          aria-label={title}
          onPointerUp={pictureTap}
          onDoubleClick={toggleFull}
          onLoadedMetadata={(e) => {
            const el = e.currentTarget;
            setDuration(el.duration);
            if (el.videoWidth && el.videoHeight) setShape(el.videoWidth / el.videoHeight);
          }}
          onDurationChange={(e) => setDuration(e.currentTarget.duration)}
          onTimeUpdate={(e) => {
            if (!scrubbing) setTime(e.currentTarget.currentTime);
            readBuffered();
          }}
          onProgress={readBuffered}
          onPlay={() => {
            setPlaying(true);
            wake();
          }}
          onPause={() => setPlaying(false)}
          onEnded={() => setPlaying(false)}
          onPlaying={() => setWaiting(false)}
          onWaiting={() => setWaiting(true)}
          onCanPlay={() => setWaiting(false)}
          onVolumeChange={(e) => {
            setVolume(e.currentTarget.volume);
            setMuted(e.currentTarget.muted);
          }}
          onResize={(e) => {
            const el = e.currentTarget;
            if (el.videoWidth && el.videoHeight) setShape(el.videoWidth / el.videoHeight);
          }}
          onError={() => {
            // through hls.js the stream's own errors are read above; this is the browser's own playing failing
            if (!hls.current) failRef.current();
          }}
        />

        {waiting && playing ? <span className="film-wait" aria-hidden="true" /> : null}

        {!playing ? (
          <button type="button" className="film-big" aria-label="Смотреть" onClick={toggle}>
            <ChromeIcon name="play" width={88} />
          </button>
        ) : null}

        <div className="film-ui">
          <div
            className="film-scrub"
            data-scrubbing={scrubbing || undefined}
            role="slider"
            tabIndex={-1}
            aria-label="Перемотка"
            aria-valuemin={0}
            aria-valuemax={Math.round(duration)}
            aria-valuenow={Math.round(time)}
            aria-valuetext={`${clock(time)} из ${clock(duration)}`}
            style={{ "--played": part(time), "--buffered": part(buffered), "--hover": hover ?? 0 } as CSSProperties}
            onPointerDown={scrubDown}
            onPointerMove={scrubMove}
            onPointerUp={scrubUp}
            onPointerCancel={scrubUp}
            onPointerLeave={() => !scrubbing && setHover(null)}
          >
            <span className="film-track">
              <span className="film-buffered" />
              <span className="film-played" />
            </span>
            <span className="film-knob" />
            {hover !== null && duration > 0 ? <span className="film-at caps tabular-nums">{clock(hover * duration)}</span> : null}
          </div>

          <div className="film-row">
            <button type="button" className="film-btn" aria-label={playing ? "Пауза" : "Смотреть"} onClick={toggle}>
              <svg viewBox="0 0 20 20" aria-hidden="true">
                {playing ? <path d="M6 4v12M14 4v12" /> : <path className="film-fill" d="M6 3.5v13l10.5-6.5z" />}
              </svg>
            </button>
            <p className="caps tabular-nums film-time">
              {clock(time)} <span className="film-total opacity-60">/ {clock(duration)}</span>
            </p>

            <span className="flex-1" />

            {qualities.length > 1 ? (
              <div ref={menuRef} className="relative">
                <button
                  type="button"
                  className="film-btn caps film-quality"
                  aria-haspopup="menu"
                  aria-expanded={menu}
                  aria-label={`Качество: ${chosenLabel}`}
                  onClick={() => setMenu((open) => !open)}
                >
                  {chosenLabel}
                </button>
                {menu ? (
                  <div className="film-menu" role="menu" aria-label="Качество">
                    {[{ level: -1, label: "Авто" }, ...qualities].map((q) => (
                      <button
                        key={q.level}
                        type="button"
                        role="menuitemradio"
                        aria-checked={chosen === q.level}
                        className="caps"
                        onClick={() => pick(q.level)}
                      >
                        <span>{q.label}</span>
                        {q.level === -1 && chosen === -1 && nowLabel ? <span className="opacity-70">сейчас {nowLabel}</span> : null}
                      </button>
                    ))}
                  </div>
                ) : null}
              </div>
            ) : null}

            <div className="film-sound">
              <button type="button" className="film-btn" aria-label={quiet ? "Включить звук" : "Выключить звук"} onClick={toggleMute}>
                <svg viewBox="0 0 20 20" aria-hidden="true">
                  <path d="M3 7.5h3l4-3.5v12l-4-3.5H3z" />
                  {quiet ? <path d="M13 7.5l5 5M18 7.5l-5 5" /> : <path d="M13.5 7a4 4 0 0 1 0 6M15.5 4.5a7.5 7.5 0 0 1 0 11" />}
                </svg>
              </button>
              <input
                type="range"
                min={0}
                max={1}
                step={0.05}
                value={quiet ? 0 : volume}
                aria-label="Громкость"
                className="film-volume"
                style={{ "--v": `${(quiet ? 0 : volume) * 100}%` } as CSSProperties}
                onChange={(e) => {
                  const el = video.current;
                  if (!el) return;
                  el.volume = Number(e.target.value);
                  el.muted = el.volume === 0;
                }}
              />
            </div>

            <button type="button" className="film-btn" aria-label={full ? "Свернуть" : "Во весь экран"} onClick={toggleFull}>
              <svg viewBox="0 0 20 20" aria-hidden="true">
                {full ? <path d="M7.5 3v4.5H3M12.5 3v4.5H17M7.5 17v-4.5H3M12.5 17v-4.5H17" /> : <path d="M3 7.5V3h4.5M17 7.5V3h-4.5M3 12.5V17h4.5M17 12.5V17h-4.5" />}
              </svg>
            </button>
          </div>
        </div>

        {refused && muted && playing ? (
          <button type="button" className="film-unmute caps" onClick={toggleMute}>
            Включить звук
          </button>
        ) : null}
      </div>
    </div>
  );
}

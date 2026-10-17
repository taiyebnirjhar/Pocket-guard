// The alert emails. Fixed wording: the app only supplies the owner's name, the time and the
// language, so the function can't be used to send arbitrary mail.

export type Kind = "sos" | "false_alarm" | "test";
export type Lang = "en" | "bn";

export interface Email {
  subject: string;
  text: string;
  html: string;
}

const words = {
  en: {
    sos: {
      subject: (n: string) => `${n} needs help (SOS)`,
      lines: (n: string, t: string) => [
        `${n} started an SOS in Pocket Guard at ${t}.`,
        "See where they are, live:",
      ],
      after: "If you can't reach them, call your local emergency number.",
    },
    false_alarm: {
      subject: (n: string) => `${n}: false alarm, they're safe`,
      lines: (
        n: string,
        _t: string,
      ) => [`${n} stopped the SOS from Pocket Guard: it was a false alarm and they're safe.`],
      after: "",
    },
    test: {
      subject: (n: string) => `Test: ${n} added you as an emergency contact`,
      lines: (n: string, _t: string) => [
        `This is a test from Pocket Guard. ${n} added you as an emergency contact.`,
        "If they start an SOS, you'll get an email like this with a link to their live location.",
      ],
      after: "",
    },
    footer: (n: string) => `You get this email because ${n} added you as an emergency contact in Pocket Guard.`,
  },
  bn: {
    sos: {
      subject: (n: string) => `${n}-এর সাহায্য দরকার (SOS)`,
      lines: (n: string, t: string) => [
        `${n} ${t}-এ Pocket Guard-এ SOS চালু করেছেন।`,
        "তিনি এখন কোথায় আছেন, সরাসরি দেখুন:",
      ],
      after: "যোগাযোগ করতে না পারলে স্থানীয় জরুরি নম্বরে (বাংলাদেশে ৯৯৯) কল করুন।",
    },
    false_alarm: {
      subject: (n: string) => `${n}: ভুল সতর্কতা, তিনি নিরাপদ`,
      lines: (n: string, _t: string) => [`${n} Pocket Guard-এর SOS বন্ধ করেছেন: এটি ভুল সতর্কতা ছিল, তিনি নিরাপদ।`],
      after: "",
    },
    test: {
      subject: (n: string) => `পরীক্ষা: ${n} আপনাকে জরুরি যোগাযোগ হিসেবে যোগ করেছেন`,
      lines: (n: string, _t: string) => [
        `এটি Pocket Guard-এর একটি পরীক্ষা। ${n} আপনাকে জরুরি যোগাযোগ হিসেবে যোগ করেছেন।`,
        "তিনি SOS চালু করলে তাঁর সরাসরি অবস্থানের লিংকসহ এরকম একটি ইমেইল পাবেন।",
      ],
      after: "",
    },
    footer: (n: string) => `${n} আপনাকে Pocket Guard-এ জরুরি যোগাযোগ হিসেবে যোগ করেছেন, তাই এই ইমেইল পেয়েছেন।`,
  },
};

function escape(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);
}

/** Names come from the phone: one line, no control characters, at most 60 characters. */
export function cleanName(name: unknown): string {
  return String(name ?? "").replace(/[\p{Cc}\p{Cf}]/gu, " ").replace(/\s+/g, " ").trim().slice(0, 60);
}

/** "14:05" or "2:05 PM" style times only. */
export function cleanTime(time: unknown): string {
  const t = String(time ?? "").trim();
  return /^[0-9০-৯]{1,2}[:.][0-9০-৯]{2}(\s?[AaPp]\.?[Mm]\.?)?$/.test(t) ? t : "";
}

export function buildEmail(kind: Kind, lang: Lang, name: string, time: string, link: string | null): Email {
  const w = words[lang];
  const k = w[kind];
  const lines = k.lines(name, time || "—");
  const text = [
    ...lines,
    ...(link ? [link] : []),
    ...(k.after ? ["", k.after] : []),
    "",
    w.footer(name),
  ].join("\n");
  const html = `<!doctype html><html><body style="font-family:sans-serif;font-size:16px;line-height:1.5">` +
    lines.map((l) => `<p>${escape(l)}</p>`).join("") +
    (link
      ? `<p><a href="${escape(link)}" style="display:inline-block;padding:12px 20px;background:#7c4dff;color:#fff;` +
        `border-radius:12px;text-decoration:none;font-weight:bold">${
          lang === "bn" ? "সরাসরি অবস্থান দেখুন" : "See live location"
        }</a></p>` +
        `<p style="font-size:13px;color:#666">${escape(link)}</p>`
      : "") +
    (k.after ? `<p><b>${escape(k.after)}</b></p>` : "") +
    `<p style="font-size:12px;color:#888">${escape(w.footer(name))}</p></body></html>`;
  return { subject: k.subject(name), text, html };
}

const EMAIL = /^[^\s@<>()[\],;:"]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,24}$/;

/** At most five distinct, plausible addresses; anything else is dropped. */
export function cleanRecipients(list: unknown): string[] {
  if (!Array.isArray(list)) return [];
  const seen = new Set<string>();
  for (const item of list) {
    const e = String(item ?? "").trim().toLowerCase();
    if (EMAIL.test(e)) seen.add(e);
  }
  return [...seen].slice(0, 5);
}

// The version and size of the APK CI published next to this page (download/latest.json).
fetch("/download/latest.json", { cache: "no-cache" })
  .then((r) => (r.ok ? r.json() : null))
  .then((latest) => {
    if (!latest || !latest.version) return;
    const mb = latest.size ? `${(latest.size / 1048576).toFixed(1)} MB` : null;
    document.querySelectorAll("[data-version]").forEach((el) => (el.textContent = `v${latest.version}`));
    if (mb) document.querySelectorAll("[data-size]").forEach((el) => (el.textContent = mb));
  })
  .catch(() => {});

// Fade sections in as they scroll into view (the CSS skips this when reduced motion is asked for).
const items = document.querySelectorAll(".reveal");
if ("IntersectionObserver" in window && !matchMedia("(prefers-reduced-motion: reduce)").matches) {
  const seen = new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting) {
        e.target.classList.add("in");
        seen.unobserve(e.target);
      }
    }
  }, { rootMargin: "0px 0px -8% 0px" });
  items.forEach((el) => seen.observe(el));
} else {
  items.forEach((el) => el.classList.add("in"));
}

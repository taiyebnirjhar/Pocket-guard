// The live location page. The link from the alert ends in #t=<token>; the fragment never
// reaches any server, and only this page sends it to Pocket Guard's database to read the trail.
(function () {
  "use strict";
  var cfg = window.POCKET_GUARD || {};
  var bn = (navigator.language || "").toLowerCase().indexOf("bn") === 0;
  var T = bn ? {
    title: function (n) { return n + "-এর অবস্থান"; },
    sos: "SOS চলছে", ended: "SOS শেষ", safe: "ভুল সতর্কতা, তিনি নিরাপদ",
    seen: "শেষ দেখা", ago: function (m) { return m < 1 ? "এইমাত্র" : m + " মিনিট আগে"; },
    within: function (a) { return a + " মি. এর মধ্যে"; }, battery: "ব্যাটারি", started: "শুরু",
    gmaps: "Google Maps-এ খুলুন", directions: "পথ দেখান",
    waiting: "অবস্থানের অপেক্ষায়… পাতাটি নিজে থেকেই হালনাগাদ হবে।",
    note: "পাতাটি প্রতি ১০ সেকেন্ডে হালনাগাদ হয়। যোগাযোগ করতে না পারলে জরুরি নম্বরে (বাংলাদেশে ৯৯৯) কল করুন।",
    gone: "এই লিংকটি আর কাজ করে না। SOS শেষ হওয়ার এক দিন পর অবস্থান মুছে যায়।",
    offline: "সংযোগ নেই, আবার চেষ্টা হচ্ছে…",
    recordings: "রেকর্ডিং (SOS চালু হওয়ার পর থেকে)", video: function (n, t) { return "▶ ভিডিও " + n + " · " + t; },
    photo: function (n, t) { return "ছবি " + n + " · " + t; },
    loading: "খুলছে…", failed: "খোলা গেল না, আবার চাপুন",
  } : {
    title: function (n) { return n + "'s location"; },
    sos: "SOS ACTIVE", ended: "SOS ENDED", safe: "FALSE ALARM, THEY'RE SAFE",
    seen: "Last seen", ago: function (m) { return m < 1 ? "just now" : m + " min ago"; },
    within: function (a) { return "within " + a + " m"; }, battery: "Battery", started: "Started",
    gmaps: "Open in Google Maps", directions: "Directions",
    waiting: "Waiting for the first location… this page updates by itself.",
    note: "Updates every 10 seconds. If you can't reach them, call your local emergency number.",
    gone: "This link no longer works. The location is deleted a day after the SOS ends.",
    offline: "No connection, trying again…",
    recordings: "Recordings (from when SOS started)", video: function (n, t) { return "▶ Video " + n + " · " + t; },
    photo: function (n, t) { return "Photo " + n + " · " + t; },
    loading: "Opening…", failed: "Couldn't open it, tap to try again",
  };
  if (bn) document.documentElement.lang = "bn";

  var $ = function (id) { return document.getElementById(id); };
  var token = (location.hash.match(/[#&]t=([0-9a-f]{48})/) || [])[1];
  var map = null, trail = null, marker = null, circle = null, fitted = false, timer = null;
  var listed = {}, counts = { video: 0, photo: 0 };

  function show(id, on) { $(id).classList.toggle("hidden", !on); }
  function message(text) {
    $("message").textContent = text;
    show("message", true);
    ["head", "map", "actions", "media"].forEach(function (id) { show(id, false); });
  }
  function time(iso) {
    return new Date(iso).toLocaleTimeString(bn ? "bn-BD" : undefined, { hour: "numeric", minute: "2-digit" });
  }

  function render(v) {
    show("message", false);
    ["head", "map", "actions"].forEach(function (id) { show(id, true); });
    var st = $("status");
    st.className = v.status === "active" ? "sos" : v.status === "false_alarm" ? "safe" : "";
    st.textContent = v.status === "active" ? T.sos : v.status === "false_alarm" ? T.safe : T.ended;
    $("title").textContent = T.title(v.name);
    document.title = T.title(v.name) + " · Pocket Guard";

    var pts = v.points || [];
    var last = pts[pts.length - 1];
    var facts = [T.started + " " + time(v.started_at)];
    if (last) {
      var mins = Math.max(0, Math.round((Date.now() - new Date(last[3]).getTime()) / 60000));
      facts.unshift(T.seen + " " + T.ago(mins) + (last[2] != null ? " · " + T.within(Math.round(last[2])) : ""));
    }
    if (v.battery != null) facts.push(T.battery + " " + v.battery + "%");
    $("facts").textContent = facts.join(" · ");
    $("note").textContent = last ? T.note : T.waiting;
    renderMedia(v.media || []);

    if (!map) {
      map = L.map("map", { zoomControl: true });
      L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19,
        // OpenStreetMap's tile servers want to know which site asks (the private token is in the
        // address's #fragment, which is never sent).
        referrerPolicy: "strict-origin-when-cross-origin",
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      }).addTo(map);
      map.setView([23.8103, 90.4125], 11);
    }
    if (!last) { show("actions", true); $("gmaps").classList.add("hidden"); $("directions").classList.add("hidden"); return; }
    $("gmaps").classList.remove("hidden");
    $("directions").classList.remove("hidden");

    var latlngs = pts.map(function (p) { return [p[0], p[1]]; });
    var here = latlngs[latlngs.length - 1];
    if (!trail) {
      trail = L.polyline(latlngs, { color: "#6a4fd1", weight: 4, opacity: 0.7 }).addTo(map);
      circle = L.circle(here, { radius: last[2] || 0, color: "#c62828", weight: 1, fillOpacity: 0.12 }).addTo(map);
      marker = L.circleMarker(here, { radius: 9, color: "#fff", weight: 3, fillColor: "#c62828", fillOpacity: 1 }).addTo(map);
    } else {
      trail.setLatLngs(latlngs);
      circle.setLatLng(here).setRadius(last[2] || 0);
      marker.setLatLng(here);
    }
    if (!fitted) {
      if (latlngs.length > 1) map.fitBounds(trail.getBounds(), { padding: [32, 32], maxZoom: 17 });
      else map.setView(here, 16);
      fitted = true;
    }
    var q = here[0].toFixed(6) + "," + here[1].toFixed(6);
    $("gmaps").href = "https://www.google.com/maps/search/?api=1&query=" + q;
    $("gmaps").textContent = T.gmaps;
    $("directions").href = "https://www.google.com/maps/dir/?api=1&destination=" + q;
    $("directions").textContent = T.directions;
  }

  // Recordings: listed by id; a tap asks for a link that works for a few minutes, then plays it.
  function renderMedia(items) {
    show("media", items.length > 0);
    $("media-title").textContent = T.recordings;
    items.forEach(function (m) {
      var id = m[0], kind = m[1] === "photo" ? "photo" : "video";
      if (listed[id]) return;
      var item = document.createElement("div");
      item.className = "item";
      var button = document.createElement("button");
      counts[kind]++;
      var label = T[kind](counts[kind], time(m[2]));
      button.textContent = label;
      button.onclick = function () { play(id, kind, item, button, label); };
      item.appendChild(button);
      $("media-list").appendChild(item);
      listed[id] = true;
    });
  }

  function play(id, kind, item, button, label) {
    button.textContent = T.loading;
    fetch(cfg.url + "/functions/v1/media", {
      method: "POST",
      headers: { apikey: cfg.key, "Content-Type": "application/json" },
      body: JSON.stringify({ action: "view", token: token, media_id: id }),
    }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (res) {
      var el;
      if (kind === "photo") {
        el = document.createElement("img");
        el.alt = label;
      } else {
        el = document.createElement("video");
        el.controls = true;
        el.playsInline = true;
        el.autoplay = true;
      }
      el.src = res.url;
      item.replaceChild(el, button);
    }).catch(function () {
      button.textContent = T.failed;
      setTimeout(function () { button.textContent = label; }, 4000);
    });
  }

  function load() {
    fetch(cfg.url + "/rest/v1/rpc/view_incident", {
      method: "POST",
      headers: { apikey: cfg.key, "Content-Type": "application/json" },
      body: JSON.stringify({ token: token }),
    }).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.json();
    }).then(function (v) {
      if (!v) { message(T.gone); clearInterval(timer); return; }
      render(v);
      window.POCKET_GUARD_LOADED = true; // for the end-to-end test
    }).catch(function () {
      if (!map) message(T.offline);
    });
  }

  if (!token || !cfg.url || !cfg.key || cfg.url.indexOf("__") === 0) { message(T.gone); return; }
  load();
  timer = setInterval(load, 10000);
})();

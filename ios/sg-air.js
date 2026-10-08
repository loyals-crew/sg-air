// SG Air — 24-hr PSI and 1-hr PM2.5 for the Singapore region you are in.
// A widget for the Scriptable app (https://scriptable.app). Data: NEA via data.gov.sg.
//
// Optional widget parameter (long-press the widget > Edit Widget > Parameter), words in any order:
//   north | south | east | west | central   always show that region instead of following your location
//   light | dark                            force the widget's appearance instead of following the system
// Example: "east dark"

const PSI_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi";
const PM25_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25";

// Coordinates are the region label points from the NEA API's regionMetadata.
const REGIONS = [
  { key: "north", label: "North", lat: 1.41803, lon: 103.82 },
  { key: "south", label: "South", lat: 1.29587, lon: 103.82 },
  { key: "east", label: "East", lat: 1.35735, lon: 103.94 },
  { key: "west", label: "West", lat: 1.35735, lon: 103.7 },
  { key: "central", label: "Central", lat: 1.35735, lon: 103.82 },
];
const OUTSIDE = "outside";

const PALETTE = {
  card: ["#FFFFFF", "#1C2226"],
  text: ["#1A1F23", "#ECEFF1"],
  muted: ["#667079", "#98A2AA"],
  good: ["#2E9E5B", "#55C583"],
  moderate: ["#2F7FD6", "#6AA9EE"],
  unhealthy: ["#C98A00", "#F0B93A"],
  veryUnhealthy: ["#E0621A", "#F58A4B"],
  hazardous: ["#D12F2F", "#F26B6B"],
};

const options = (args.widgetParameter || "").toLowerCase().split(/[\s,]+/);
const fixedRegion = REGIONS.find((r) => options.includes(r.key));
const theme = options.includes("dark") ? "dark" : options.includes("light") ? "light" : "system";

function color(name, forced) {
  const [light, dark] = PALETTE[name];
  if (forced === "light") return new Color(light);
  if (forced === "dark") return new Color(dark);
  return Color.dynamic(new Color(light), new Color(dark));
}

// NEA descriptors for 24-hr PSI.
function psiBand(v) {
  if (v <= 50) return { label: "Good", color: "good" };
  if (v <= 100) return { label: "Moderate", color: "moderate" };
  if (v <= 200) return { label: "Unhealthy", color: "unhealthy" };
  if (v <= 300) return { label: "Very unhealthy", color: "veryUnhealthy" };
  return { label: "Hazardous", color: "hazardous" };
}

// NEA bands for 1-hr PM2.5 in µg/m³.
function pm25Band(v) {
  if (v <= 55) return { label: "Normal", color: "good" };
  if (v <= 150) return { label: "Elevated", color: "unhealthy" };
  if (v <= 250) return { label: "High", color: "veryUnhealthy" };
  return { label: "Very high", color: "hazardous" };
}

// Nearest region to a point, or OUTSIDE when the point is not in Singapore (rough bounding box).
function nearestRegion(lat, lon) {
  if (lat < 1.15 || lat > 1.475 || lon < 103.59 || lon > 104.1) return OUTSIDE;
  let best = REGIONS[0];
  for (const r of REGIONS) {
    if (Math.hypot(r.lat - lat, r.lon - lon) < Math.hypot(best.lat - lat, best.lon - lon)) best = r;
  }
  return best.key;
}

// "2:00 PM" from the API's Singapore-time timestamp, e.g. 2026-10-08T14:00:00+08:00.
function formatTime(iso) {
  const hour = Number(iso.slice(11, 13));
  const time = `${hour % 12 || 12}:${iso.slice(14, 16)} ${hour < 12 ? "AM" : "PM"}`;
  const stale = Date.now() - new Date(iso).getTime() > 3 * 60 * 60 * 1000;
  return stale ? `Old · ${time}` : time;
}

const fm = FileManager.local();
const cachePath = fm.joinPath(fm.libraryDirectory(), "sg-air.json");

function readCache() {
  try {
    return fm.fileExists(cachePath) ? JSON.parse(fm.readString(cachePath)) : {};
  } catch (e) {
    return {};
  }
}

async function latest(url, reading) {
  const body = await new Request(url).loadJSON();
  if (body.code !== 0) throw new Error(body.errorMsg || "data.gov.sg error");
  const item = body.data.items[0];
  return { values: item.readings[reading], time: item.timestamp };
}

// Fetches readings and the current region, falling back to the last saved ones when either fails.
async function load() {
  const state = readCache();
  try {
    const [psi, pm25] = await Promise.all([
      latest(PSI_URL, "psi_twenty_four_hourly"),
      latest(PM25_URL, "pm25_one_hourly"),
    ]);
    state.data = { psi: psi.values, pm25: pm25.values, psiTime: psi.time, pm25Time: pm25.time };
  } catch (e) {
    state.error = String(e.message || e);
  }
  if (!fixedRegion) {
    try {
      Location.setAccuracyToThreeKilometers();
      const here = await Location.current();
      state.region = nearestRegion(here.latitude, here.longitude);
    } catch (e) {
      // No fix (common inside a widget): keep the last known region.
    }
  }
  fm.writeString(cachePath, JSON.stringify({ data: state.data, region: state.region }));
  return state;
}

// The headline reading: the user's region, or the highest Singapore reading when the region is unknown.
function shown(state) {
  const { psi, pm25 } = state.data;
  const region = fixedRegion || REGIONS.find((r) => r.key === state.region);
  if (region) {
    const note = fixedRegion ? "Chosen region" : "Based on your location";
    return { key: region.key, title: region.label, note, psi: psi[region.key], pm25: pm25[region.key] };
  }
  const note = state.region === OUTSIDE ? "You appear to be outside Singapore" : "Location not available yet";
  return {
    key: null,
    title: "Singapore (highest)",
    note,
    psi: Math.max(...Object.values(psi)),
    pm25: Math.max(...Object.values(pm25)),
  };
}

function addText(parent, text, font, colorName, forced) {
  const t = parent.addText(String(text));
  t.font = font;
  t.lineLimit = 1;
  t.minimumScaleFactor = 0.6;
  if (colorName) t.textColor = color(colorName, forced);
  return t;
}

function homeWidget(state) {
  const w = new ListWidget();
  w.backgroundColor = color("card", theme);
  w.setPadding(14, 14, 14, 14);
  if (!state.data) {
    addText(w, "SG Air", Font.boldSystemFont(14), "text", theme);
    addText(w, state.error || "No data yet", Font.systemFont(11), "muted", theme).lineLimit = 3;
    return w;
  }
  const s = shown(state);
  addText(w, s.title, Font.boldSystemFont(14), "text", theme);
  addText(w, formatTime(state.data.psiTime), Font.systemFont(11), "muted", theme);
  w.addSpacer(6);
  const row = w.addStack();
  const columns = [
    ["PSI 24h", s.psi, psiBand(s.psi)],
    ["PM2.5 1h", s.pm25, pm25Band(s.pm25)],
  ];
  for (const [label, value, band] of columns) {
    const col = row.addStack();
    col.layoutVertically();
    addText(col, label, Font.systemFont(11), "muted", theme);
    addText(col, value, Font.boldSystemFont(28), band.color, theme);
    addText(col, band.label, Font.systemFont(11), "muted", theme);
    row.addSpacer();
  }
  return w;
}

// Lock-screen widgets are drawn in a single tint, so they carry the band as text instead of colour.
function lockWidget(state, family) {
  const w = new ListWidget();
  if (!state.data) {
    addText(w, "SG Air: no data", Font.systemFont(12));
    return w;
  }
  const s = shown(state);
  if (family === "accessoryInline") {
    addText(w, `PSI ${s.psi} · PM2.5 ${s.pm25}`, Font.systemFont(12));
  } else if (family === "accessoryCircular") {
    addText(w, "PSI", Font.systemFont(10)).centerAlignText();
    addText(w, s.psi, Font.boldSystemFont(20)).centerAlignText();
  } else {
    addText(w, s.title, Font.boldSystemFont(13));
    addText(w, `PSI ${s.psi} · ${psiBand(s.psi).label}`, Font.systemFont(13));
    addText(w, `PM2.5 ${s.pm25} · ${pm25Band(s.pm25).label}`, Font.systemFont(13));
  }
  return w;
}

function cell(row, title, subtitle, colorName, weight) {
  const c = row.addText(String(title), subtitle);
  c.widthWeight = weight;
  c.titleFont = Font.boldSystemFont(20);
  c.subtitleFont = Font.systemFont(12);
  if (colorName) c.titleColor = color(colorName);
  c.subtitleColor = color("muted");
  return c;
}

// The full view shown when the widget is tapped: your region first, then all five.
async function showTable(state) {
  const table = new UITable();
  table.showSeparators = true;
  const title = new UITableRow();
  title.isHeader = true;
  title.addText("SG Air", state.data ? `NEA readings as of ${formatTime(state.data.psiTime)}` : state.error);
  table.addRow(title);
  if (state.data) {
    const s = shown(state);
    const header = new UITableRow();
    for (const [text, weight] of [["Region", 40], ["PSI · 24 hr", 30], ["PM2.5 · 1 hr", 30]]) {
      const c = header.addText(text);
      c.widthWeight = weight;
      c.titleFont = Font.systemFont(12);
      c.titleColor = color("muted");
    }
    const rows = [{ label: s.title, note: s.note, psi: s.psi, pm25: s.pm25 }];
    for (const r of REGIONS) {
      const note = r.key === s.key ? "You are here" : "";
      rows.push({ label: r.label, note, psi: state.data.psi[r.key], pm25: state.data.pm25[r.key] });
    }
    rows.forEach((r, i) => {
      if (i === 1) table.addRow(header);
      const row = new UITableRow();
      row.height = 60;
      cell(row, r.label, r.note, null, 40).titleFont = i === 0 ? Font.boldSystemFont(18) : Font.systemFont(17);
      cell(row, r.psi, psiBand(r.psi).label, psiBand(r.psi).color, 30);
      cell(row, r.pm25, pm25Band(r.pm25).label, pm25Band(r.pm25).color, 30);
      table.addRow(row);
    });
  }
  const source = new UITableRow();
  const c = source.addText("", "Source: National Environment Agency (NEA) via data.gov.sg. Updated hourly.");
  c.subtitleFont = Font.systemFont(11);
  c.subtitleColor = color("muted");
  table.addRow(source);
  await table.present();
}

const state = await load();
if (config.runsInWidget) {
  const family = config.widgetFamily || "small";
  const widget = family.startsWith("accessory") ? lockWidget(state, family) : homeWidget(state);
  // iOS decides the real schedule; this only asks it not to refresh sooner than 15 minutes.
  widget.refreshAfterDate = new Date(Date.now() + 15 * 60 * 1000);
  Script.setWidget(widget);
} else {
  await showTable(state);
}
Script.complete();

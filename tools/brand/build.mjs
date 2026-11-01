// Renders front/public/logo.svg into the PNGs and the .ico the page and Google's consent screen use.
import { readFileSync, writeFileSync } from "node:fs";
import { Resvg } from "@resvg/resvg-js";

const pub = new URL("../../front/public/", import.meta.url);
const logo = readFileSync(new URL("logo.svg", pub), "utf8");
// iOS rounds the corners itself: its icon is the square, not the rounded tile.
const square = logo.replace('rx="28" ', "");

const render = (svg, width) =>
  new Resvg(svg, { fitTo: { mode: "width", value: width } }).render().asPng();

const png = {
  "logo-120.png": render(logo, 120),
  "logo-512.png": render(logo, 512),
  "favicon-32.png": render(logo, 32),
  "apple-touch-icon.png": render(square, 180),
};
for (const [name, data] of Object.entries(png)) {
  writeFileSync(new URL(name, pub), data);
}

// An .ico is a header, one directory entry per image and the images: PNG data is allowed inside.
const sizes = [16, 32, 48];
const images = sizes.map((size) => render(logo, size));
const header = Buffer.alloc(6 + 16 * sizes.length);
header.writeUInt16LE(1, 2);
header.writeUInt16LE(sizes.length, 4);
let offset = header.length;
sizes.forEach((size, i) => {
  const entry = 6 + 16 * i;
  header.writeUInt8(size, entry);
  header.writeUInt8(size, entry + 1);
  header.writeUInt16LE(1, entry + 4);
  header.writeUInt16LE(32, entry + 6);
  header.writeUInt32LE(images[i].length, entry + 8);
  header.writeUInt32LE(offset, entry + 12);
  offset += images[i].length;
});
writeFileSync(new URL("favicon.ico", pub), Buffer.concat([header, ...images]));

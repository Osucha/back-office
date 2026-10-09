// npm run manual:link (first step of start / build / test:ci) - makes the user manual in
// ../docs/manual available to the Angular build, which only copies assets from inside the workspace
// ("asset path must be within the workspace root"). Creates `.manual` as a link to docs/manual (a
// junction on Windows, no admin rights needed), so the dev server serves edits to the manual live;
// where links are not possible it copies the files instead. `.manual` is git-ignored. The whole
// folder is linked, so translations (`docs/manual/pl/`, served at `/manual/pl/`) come along (ADR 0026).
import {
  cpSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readlinkSync,
  rmSync,
  symlinkSync,
} from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(root, '..', 'docs', 'manual');
const target = join(root, '.manual');

if (!existsSync(source)) {
  // Builds without the docs (should not happen in this repository): an empty manual, help says
  // "This chapter is being written." instead of failing.
  console.warn(`manual:link: ${source} not found; the in-app manual will be empty.`);
  rmSync(target, { recursive: true, force: true });
  mkdirSync(target, { recursive: true });
  process.exit(0);
}

let current = null;
try {
  const st = lstatSync(target);
  current = st.isSymbolicLink() ? resolve(root, readlinkSync(target)) : 'copy';
} catch {
  current = null;
}
if (current && current !== 'copy' && resolve(current) === source) process.exit(0);

rmSync(target, { recursive: true, force: true });
try {
  symlinkSync(source, target, process.platform === 'win32' ? 'junction' : 'dir');
  console.log(`manual:link: .manual -> ${source}`);
} catch (err) {
  cpSync(source, target, { recursive: true });
  console.log(`manual:link: copied ${source} to .manual (${err.code ?? err.message})`);
}

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseDevVars } from '../dev-vars.mjs';

test('reads KEY=value lines and ignores comments and blanks', () => {
  assert.deepEqual(parseDevVars('# note\n\nOPENAI_API_KEY=abc123\nOTHER = x y \n'), { OPENAI_API_KEY: 'abc123', OTHER: 'x y' });
});
test('strips matching quotes and tolerates Windows line endings', () => {
  assert.deepEqual(parseDevVars('A="one"\r\nB=\'two\'\r\n'), { A: 'one', B: 'two' });
});
test('an unfilled key is an empty string, so the dev server ignores it', () => {
  assert.deepEqual(parseDevVars('OPENAI_API_KEY=\n'), { OPENAI_API_KEY: '' });
});
test('junk lines are skipped', () => {
  assert.deepEqual(parseDevVars('no equals sign\n=novalue\nOK=1'), { OK: '1' });
});

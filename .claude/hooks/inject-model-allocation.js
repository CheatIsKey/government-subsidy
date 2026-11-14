#!/usr/bin/env node
// 세션 시작·압축 직후에 '작업별 모델 배분 기준'을 컨텍스트로 넣는다.
// 개발 워크플로우 본문은 CLAUDE.md가 매 세션 자동으로 읽히므로 여기서 다시 넣지 않는다.
// Claude Code와 Codex가 같은 스크립트를 쓴다 — 두 도구 모두 stdout의 hookSpecificOutput.additionalContext를 받는다.
const fs = require('fs');
const path = require('path');

let event = 'SessionStart';
try {
  const input = JSON.parse(fs.readFileSync(0, 'utf8') || '{}');
  if (input.hook_event_name) event = input.hook_event_name;
} catch (_) { /* 입력이 없어도 주입은 한다 */ }

const root = process.env.CLAUDE_PROJECT_DIR || path.resolve(__dirname, '..', '..');
const file = path.join(root, '.claude', 'rules', 'model-allocation.md');

let body;
try {
  body = fs.readFileSync(file, 'utf8');
} catch (e) {
  body = `(모델 배분 기준 파일을 읽지 못했다: ${file} — ${e.message}. 사용자에게 알리고 진행하라.)`;
}

const context = [
  '<model-allocation priority="always">',
  '작업을 에이전트에게 맡길 때 아래 기준으로 모델 수준을 고른다. 절차는 dev-workflow 스킬을 따른다.',
  body.trim(),
  '</model-allocation>',
].join('\n');

process.stdout.write(JSON.stringify({
  hookSpecificOutput: { hookEventName: event, additionalContext: context },
}));

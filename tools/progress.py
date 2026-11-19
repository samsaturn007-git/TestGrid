#!/usr/bin/env python3
"""Offline TestGrid PRD tracker (v2). Standard library only, Python 3.9+.

Supports human-reviewed progress; it does not prove code correctness.
Usage: python3 tools/progress.py --help
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STORE = ROOT / 'progress.json'
REPORT = ROOT / 'PROGRESS.md'
EVIDENCE_DIR = ROOT / 'docs' / 'evidence'

TEMPLATE_PLACEHOLDERS = (
    '[exact path]', '[actual SHA]', '[human]', '[UTC date]',
    '[pass / fail / blocked]', '<command>', '<actual output and exit code>',
)
TASK_LINE = re.compile(r'^- \[[ x]\] \*\*(T\d+)\*\* — (.+)$')
AC_ID = re.compile(r'\*\*(AC-\d+):?\*\*')

MILESTONES = [
    '- **G0 Design:** PRD-00.',
    '- **G1 First runnable run:** PRD-01 to PRD-03.',
    '- **G2 Durable system:** PRD-04 to PRD-06.',
    '- **G3 Portfolio MVP:** PRD-07 to PRD-09.',
    '- **G4 Engineering differentiation:** PRD-10 to PRD-12.',
    '- **G5 Presentation and honest release:** PRD-13.',
    '- **Optional:** PRD-14 to PRD-15.',
]


def load():
    return json.loads(STORE.read_text(encoding='utf-8'))


def save(data):
    data['updated_at'] = datetime.now(timezone.utc).isoformat(timespec='seconds')
    temp = STORE.with_suffix('.json.tmp')
    temp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    temp.replace(STORE)
    report(data)


def lookup(data, prd):
    ident = prd.upper()
    if ident not in data['prds']:
        raise ValueError(f'Unknown PRD: {prd}. Valid IDs: {", ".join(data["prds"])}')
    return ident, data['prds'][ident]


def unfinished_deps(data, entry):
    return [d for d in entry['deps'] if data['prds'][d]['status'] != 'done']


def other_active(data, ident):
    return [k for k, v in data['prds'].items() if v['status'] == 'in_progress' and k != ident]


def eligible(data, optional):
    return [(k, e) for k, e in data['prds'].items()
            if e['status'] == 'not_started' and e['optional'] == optional and not unfinished_deps(data, e)]


def core_score(data):
    core = [v for v in data['prds'].values() if not v['optional']]
    total = sum(len(v['tasks']) for v in core)
    done = sum(sum(t['done'] for t in v['tasks'].values()) for v in core)
    completed = sum(v['status'] == 'done' for v in core)
    return done, total, completed, len(core)


def prd_text(entry):
    path = ROOT / entry['filename']
    return path.read_text(encoding='utf-8') if path.is_file() else ''


def validate_evidence(entry, path):
    if not path.is_relative_to(EVIDENCE_DIR.resolve()):
        raise ValueError('Evidence must be under docs/evidence/')
    if not path.is_file() or path.stat().st_size < 400:
        raise ValueError('Evidence file absent or nearly empty; write real verification evidence')
    text = path.read_text(encoding='utf-8')
    leftovers = [p for p in TEMPLATE_PLACEHOLDERS if p in text]
    if leftovers:
        raise ValueError('Evidence still contains template placeholders: ' + ', '.join(leftovers))
    required = sorted(set(AC_ID.findall(prd_text(entry))), key=lambda a: int(a.split('-')[1]))
    missing = [ac for ac in required if ac not in text]
    if missing:
        raise ValueError('Evidence does not mention: ' + ', '.join(missing))
    if not re.search(r'\*\*Outcome:\*\*\s*pass', text, re.IGNORECASE):
        raise ValueError('Evidence outcome line must read "**Outcome:** pass" before closing')


def lint(data):
    problems = []
    for ident, e in data['prds'].items():
        text = prd_text(e)
        if not text:
            problems.append(f'{ident}: missing file {e["filename"]}')
            continue
        tasks = dict(m.groups() for m in (TASK_LINE.match(l) for l in text.splitlines()) if m)
        if set(tasks) != set(e['tasks']):
            problems.append(f'{ident}: tasks in PRD {sorted(tasks)} != tracker {sorted(e["tasks"])}')
        for dep in e['deps']:
            if dep not in data['prds']:
                problems.append(f'{ident}: unknown dependency {dep}')
    return problems


def status(data):
    checked, total, completed, num = core_score(data)
    print(f'TestGrid — core PRDs completed: {completed}/{num}; checked tasks: {checked}/{total} ({100*checked/max(total,1):.0f}%)')
    for ident, e in data['prds'].items():
        count = sum(t['done'] for t in e['tasks'].values())
        suffix = ' [OPTIONAL]' if e['optional'] else ''
        blocked = f' — {e["blocker"]}' if e['blocker'] else ''
        print(f' {ident:<6} {e["status"]:<12} {count:>2}/{len(e["tasks"])}  {e["title"]}{suffix}{blocked}')
    core = ', '.join(k for k, _ in eligible(data, False)) or 'none'
    opt = ', '.join(k for k, _ in eligible(data, True)) or 'none'
    print(f'Next eligible core PRD: {core} | optional: {opt}')


def report(data):
    checked, total, completed, num = core_score(data)
    lines = [
        '# TestGrid — Live Project Progress', '',
        '> Generated from `progress.json` by `python3 tools/progress.py report`. Status is user/agent-reported and requires independent evidence verification.', '',
        f'**Core PRDs complete:** {completed}/{num}  |  **Core checklist progress:** {checked}/{total} tasks ({100*checked/max(total,1):.1f}%)', '',
        '| PRD | Status | Task progress | Effort | Dependencies | Evidence |', '|---|---|---:|---|---|---|',
    ]
    for ident, e in data['prds'].items():
        count = sum(t['done'] for t in e['tasks'].values())
        title = ident + (' (optional)' if e['optional'] else '')
        evidence = f'[{e["evidence"]}]({e["evidence"]})' if e['evidence'] else '—'
        lines.append(f'| [{title}]({e["filename"]}) | {e["status"]} | {count}/{len(e["tasks"])} | {e["estimate"]} | {", ".join(e["deps"]) or "—"} | {evidence} |')
    lines += ['', '## Milestones', '', *MILESTONES, '', '## Next action']
    active = [(k, e) for k, e in data['prds'].items() if e['status'] in ('in_progress', 'blocked')]
    ready = eligible(data, False)
    if active:
        lines.append('Continue ' + ', '.join(f'[{k}]({e["filename"]}) ({e["status"]})' for k, e in active) + '; complete unfinished checkboxes and add genuine evidence.')
    elif ready:
        lines.append('Start ' + ', '.join(f'[{k}]({e["filename"]})' for k, e in ready) + ' and open `CODEX_PLAYBOOK.md`.')
    else:
        optional = eligible(data, True)
        lines.append('All eligible core PRDs are finished or blocked.' + (' Optional: ' + ', '.join(k for k, _ in optional) + '.' if optional else ''))
    lines += ['', '## Progress update commands', '', '```bash',
              'python3 tools/progress.py status',
              'python3 tools/progress.py lint',
              'python3 tools/progress.py start PRD-00',
              'python3 tools/progress.py check PRD-00 T1',
              'python3 tools/progress.py done PRD-00 --evidence docs/evidence/PRD-00.md',
              '```', '']
    REPORT.write_text('\n'.join(lines), encoding='utf-8')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest='cmd', required=True)
    for c in ('status', 'report', 'lint'):
        sub.add_parser(c)
    for c in ('start', 'show', 'check', 'uncheck', 'block', 'unblock', 'done'):
        p = sub.add_parser(c)
        p.add_argument('prd')
        if c in ('check', 'uncheck'):
            p.add_argument('task')
        if c == 'done':
            p.add_argument('--evidence', required=True)
        if c == 'block':
            p.add_argument('--reason', required=True)
    args = ap.parse_args()
    data = load()
    try:
        if args.cmd == 'status':
            status(data)
            return
        if args.cmd == 'report':
            report(data)
            print('Generated', REPORT)
            return
        if args.cmd == 'lint':
            problems = lint(data)
            print('\n'.join(problems) if problems else 'lint OK: tracker matches PRD files')
            raise SystemExit(1 if problems else 0)
        ident, e = lookup(data, args.prd)
        if args.cmd == 'show':
            print(f'{ident}: {e["title"]} / {e["status"]}')
            for k, t in e['tasks'].items():
                print(('✓' if t['done'] else ' ') + ' ' + k + ': ' + t['label'])
            return
        if args.cmd == 'start':
            if e['status'] == 'done':
                raise ValueError('Already complete; do not restart without review')
            blocked = unfinished_deps(data, e)
            if blocked:
                raise ValueError('Prerequisites not done: ' + ', '.join(blocked))
            if other_active(data, ident):
                raise ValueError('Only one PRD can be in progress; active: ' + ', '.join(other_active(data, ident)))
            e['status'], e['blocker'] = 'in_progress', None
        elif args.cmd in ('check', 'uncheck'):
            if e['status'] != 'in_progress':
                raise ValueError('First start this PRD')
            key = args.task.upper()
            if key not in e['tasks']:
                raise ValueError(f'Unknown task {key} for {ident}')
            e['tasks'][key]['done'] = args.cmd == 'check'
        elif args.cmd == 'block':
            if e['status'] != 'in_progress':
                raise ValueError('Only an in-progress PRD can be blocked')
            e['status'], e['blocker'] = 'blocked', args.reason.strip()
        elif args.cmd == 'unblock':
            if e['status'] != 'blocked':
                raise ValueError('Not blocked')
            if unfinished_deps(data, e):
                raise ValueError('Prerequisites still incomplete')
            if other_active(data, ident):
                raise ValueError('Only one PRD can be in progress; active: ' + ', '.join(other_active(data, ident)))
            e['status'], e['blocker'] = 'in_progress', None
        elif args.cmd == 'done':
            if e['status'] != 'in_progress':
                raise ValueError('PRD must be in progress')
            if unfinished_deps(data, e):
                raise ValueError('Prerequisite incomplete')
            remaining = [k for k, t in e['tasks'].items() if not t['done']]
            if remaining:
                raise ValueError('Unchecked tasks: ' + ', '.join(remaining))
            path = (ROOT / args.evidence).resolve()
            validate_evidence(e, path)
            e['evidence'], e['status'], e['blocker'] = str(path.relative_to(ROOT)), 'done', None
        save(data)
        print(f'{ident}: {args.cmd} OK')
        status(data)
    except ValueError as exc:
        print('ERROR:', exc, file=sys.stderr)
        raise SystemExit(2)


if __name__ == '__main__':
    main()

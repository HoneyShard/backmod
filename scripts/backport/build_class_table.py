"""Build the verified 26.2 -> 26.1.2 class-move table from both diffs and upstream's forward table."""
import re, sys, json, collections
sys.path.insert(0, '')
A = set(open('diff/classes-26.1.2.txt').read().split())
B = set(open('diff/classes-26.2.txt').read().split())

def load_moves(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        m = re.match(r'(\S+) -> (\S+)\s+\[([\d.]+)\]\s*(\w*)', line)
        if m: d[m.group(1)] = (m.group(2), float(m.group(3)))
    return d

lit = r'(?:"[^"]*"\s*(?:\+\s*"[^"]*"\s*)*)'
src = open('upstream/src/main/java/com/retromod/shim/common/Mc26_1To26_2CoreMoves.java', encoding='utf-8').read()
up = {}
for m in re.finditer(r'registerClassRedirect\(\s*(%s),\s*(%s)\)' % (lit, lit), src):
    up[''.join(re.findall(r'"([^"]*)"', m.group(1)))] = ''.join(re.findall(r'"([^"]*)"', m.group(2)))

fwd = load_moves('diff/fwd/moves.txt'); rev = load_moves('diff/rev/moves.txt')
def valid_fwd(o, n): return o in A and o not in B and n in B and n not in A

cand = {}   # new(26.2) -> {old: set(sources)}
def add(new, old, src):
    cand.setdefault(new, {}).setdefault(old, set()).add(src)

rejected = []
for o, (n, s) in fwd.items():
    if valid_fwd(o, n): add(n, o, 'diff-fwd')
    else: rejected.append((o, n, 'diff-fwd'))
for o, n in up.items():
    if valid_fwd(o, n): add(n, o, 'upstream')
    else: rejected.append((o, n, 'upstream'))
for n, (o, s) in rev.items():
    if valid_fwd(o, n): add(n, o, 'diff-rev')
    else: rejected.append((n, o, 'diff-rev(new->old)'))

table, conflicts = {}, []
for new, olds in cand.items():
    if len(olds) == 1:
        (old, srcs), = olds.items(); table[new] = (old, sorted(srcs))
    else:
        conflicts.append((new, {o: sorted(s) for o, s in olds.items()}))

# a name can be both an old name and a new name only if a chain exists; report it
chain = [n for n in table if table[n][0] in table]
with open('diff/class-table-26.2-to-26.1.2.tsv', 'w') as f:
    f.write('# Backmod: 26.2 class name -> 26.1.2 class name. Every row checked against both client jars:\n')
    f.write('# new in 26.2, new absent in 26.1.2, old in 26.1.2, old absent in 26.2.\n')
    f.write('# columns: NEW_26_2\tOLD_26_1_2\tSOURCES\n')
    for n in sorted(table): f.write(f'{n}\t{table[n][0]}\t{",".join(table[n][1])}\n')
by = collections.Counter(tuple(v[1]) for v in table.values())
print('verified rows:', len(table)); [print(' ', v, k) for k, v in by.most_common()]
print('conflicts:', len(conflicts), 'chains:', len(chain))
print('rejected candidates:', len(rejected))
for r in rejected: print('  REJECT', r)
for c in conflicts: print('  CONFLICT', c)

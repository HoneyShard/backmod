"""Compare forward/reverse class-move diffs and upstream's Mc26_1To26_2CoreMoves redirects."""
import re, sys, json, collections

def load_moves(path):
    d = {}
    for line in open(path, encoding='utf-8'):
        m = re.match(r'(\S+) -> (\S+)\s+\[([\d.]+)\]\s*(\w*)', line)
        if m:
            d[m.group(1)] = (m.group(2), float(m.group(3)), m.group(4))
    return d

def load_upstream(path):
    src = open(path, encoding='utf-8').read()
    pairs = {}
    # registerClassRedirect( "a" + "b", "c" ) with optional concatenation and whitespace
    lit = r'(?:"[^"]*"\s*(?:\+\s*"[^"]*"\s*)*)'
    for m in re.finditer(r'registerClassRedirect\(\s*(%s),\s*(%s)\)' % (lit, lit), src):
        a = ''.join(re.findall(r'"([^"]*)"', m.group(1)))
        b = ''.join(re.findall(r'"([^"]*)"', m.group(2)))
        pairs[a] = b
    return pairs

fwd = load_moves(sys.argv[1]); rev = load_moves(sys.argv[2]); up = load_upstream(sys.argv[3])
print(f'forward moves={len(fwd)} reverse moves={len(rev)} upstream literal redirects={len(up)}')

# 1. Forward diff vs upstream
f_map = {k: v[0] for k, v in fwd.items()}
both = [k for k in up if k in f_map]
agree = [k for k in both if up[k] == f_map[k]]
conflict = [k for k in both if up[k] != f_map[k]]
only_up = [k for k in up if k not in f_map]
only_f = [k for k in f_map if k not in up]
print(f'\nforward diff vs upstream: agree={len(agree)} conflict={len(conflict)} only_upstream={len(only_up)} only_diff={len(only_f)}')

# 2. Inverse of forward vs reverse diff
tgt_count = collections.Counter(f_map.values())
merges = {t for t, c in tgt_count.items() if c > 1}
inv = {}
for k, v in f_map.items():
    if v in merges: continue
    inv[v] = k
r_map = {k: v[0] for k, v in rev.items()}
i_agree = [k for k in inv if k in r_map and r_map[k] == inv[k]]
i_conf = [k for k in inv if k in r_map and r_map[k] != inv[k]]
i_only_inv = [k for k in inv if k not in r_map]
i_only_rev = [k for k in r_map if k not in inv]
print(f'inverse(forward) vs reverse diff: agree={len(i_agree)} conflict={len(i_conf)} only_inverse={len(i_only_inv)} only_reverse={len(i_only_rev)}')
print(f'forward merges (many old -> one new, not invertible): {len(merges)} targets covering {sum(tgt_count[t] for t in merges)} classes')

# 3. Upstream redirects that are injective
up_t = collections.Counter(up.values())
print(f'upstream redirects: injective={sum(1 for v in up.values() if up_t[v]==1)} non-injective={sum(1 for v in up.values() if up_t[v]>1)}')

# 4. Sanity: chains/cycles among reverse moves
cyc = [k for k, v in r_map.items() if v in r_map]
print(f'reverse moves whose target is itself a source (chain): {len(cyc)}')

json.dump({'agree': i_agree, 'conflict': [(k, inv[k], r_map[k]) for k in i_conf],
           'only_inverse': i_only_inv, 'only_reverse': i_only_rev,
           'merges': sorted(merges), 'conflict_up': [(k, up[k], f_map[k]) for k in conflict],
           'only_up': only_up}, open(sys.argv[4], 'w'), indent=1)

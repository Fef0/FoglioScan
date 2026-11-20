"""Replaces BiFPN fusion Einsum('i,i...->...', w, Concat(Unsqueeze(x_k, 0)...)) with sum_k w[k]*x_k."""
import sys, onnx
from onnx import helper, numpy_helper
import numpy as np
src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src); g = m.graph
producer = {o: n for n in g.node for o in n.output}
consts = {i.name: numpy_helper.to_array(i) for i in g.initializer}
for n in g.node:
    if n.op_type == "Constant":
        for a in n.attribute:
            if a.name == "value": consts[n.output[0]] = numpy_helper.to_array(a.t)
new_nodes, removed, count = [], set(), 0
def init(name, arr):
    g.initializer.append(numpy_helper.from_array(np.asarray(arr, dtype=np.int64), name)); return name
for n in g.node:
    if n.op_type != "Einsum":
        new_nodes.append(n); continue
    eq = [a for a in n.attribute if a.name == "equation"][0].s.decode()
    assert eq == "i,i...->...", eq
    w, stacked = n.input
    cat = producer[stacked]; assert cat.op_type == "Concat"
    xs = []
    for ci in cat.input:
        u = producer[ci]; assert u.op_type == "Unsqueeze", u.op_type
        xs.append(u.input[0])
    terms = []
    for k, x in enumerate(xs):
        p = f"{n.name}_fuse{k}"
        new_nodes.append(helper.make_node("Slice", [w, init(p+"_s", [k]), init(p+"_e", [k+1]), init(p+"_a", [0])], [p+"_w"]))
        new_nodes.append(helper.make_node("Mul", [x, p+"_w"], [p+"_m"]))
        terms.append(p+"_m")
    new_nodes.append(helper.make_node("Sum", terms, [n.output[0]]))
    count += 1
del g.node[:]; g.node.extend(new_nodes)
# Drop the now-dead Concat/Unsqueeze nodes.
used = {i for n in g.node for i in n.input} | {o.name for o in g.output}
changed = True
while changed:
    changed = False
    keep = [n for n in g.node if any(o in used for o in n.output)]
    if len(keep) != len(g.node):
        del g.node[:]; g.node.extend(keep); changed = True
        used = {i for n in g.node for i in n.input} | {o.name for o in g.output}
onnx.checker.check_model(m)
onnx.save(m, dst)
print("rewrote", count, "einsums; ops:", sorted(set(n.op_type for n in g.node)))

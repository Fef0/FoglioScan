import sys, numpy as np, onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter
from PIL import Image
d = sys.argv[1]
img = Image.open(d + "/test.png"); W, H = img.size
bgr = (np.asarray(img.resize((256, 256), Image.BILINEAR)).astype(np.float32) / 255.0)[..., ::-1]
def corners(hm):  # hm (4,h,w)
    out = []
    for c in range(4):
        ys, xs = np.nonzero(hm[c] >= 0.3)
        out.append(None if len(xs) == 0 else (round(xs.mean() / hm.shape[2] * W), round(ys.mean() / hm.shape[1] * H)))
    return out
for name in sys.argv[2:]:
    o = ort.InferenceSession(f"{d}/{name}.onnx").run(None, {"img": np.ascontiguousarray(bgr.transpose(2, 0, 1)[None])})[0][0]
    print(name, "\n  onnx   ", corners(o))
    for p in ["float32", "float16"]:
        try:
            it = Interpreter(model_path=f"{d}/out_{name}/{name}_{p}.tflite"); it.allocate_tensors()
            it.set_tensor(it.get_input_details()[0]["index"], np.ascontiguousarray(bgr[None])); it.invoke()
            t = it.get_tensor(it.get_output_details()[0]["index"])[0].transpose(2, 0, 1)
            print(f"  {p}", corners(t), "mean|diff|", float(np.abs(t - o).mean()))
        except Exception as e:
            print(f"  {p} FAIL", str(e)[:150])

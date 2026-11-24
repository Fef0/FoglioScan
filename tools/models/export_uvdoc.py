"""Export UVDoc (MIT, SIGGRAPH Asia 2023) to ONNX, for conversion to LiteRT.

Usage:
    git clone https://github.com/tanguymagne/UVDoc
    python tools/models/export_uvdoc.py UVDoc uvdoc.onnx

The checkpoint is loaded with ``weights_only=True``: only tensors are deserialized, no code from
the pickle runs. Only the 2D grid head is exported (the 3D head is a training aid).

Model contract
    input  "img":  1×3×712×488 float32, RGB in [0, 1] (NCHW; H=712, W=488)
    output "grid": 1×2×45×31  float32, (x, y) in [-1, 1] over the input image, for each point
                   of a 31 (wide) × 45 (tall) grid laid evenly over the flattened page
                   (``grid_sample`` convention, align_corners=True).
"""
import sys
from pathlib import Path

import torch


def main(repo: str, out: str) -> None:
    sys.path.insert(0, str(Path(repo).resolve()))
    from model import UVDocnet  # noqa: E402  (plain torch.nn modules, see model.py)

    model = UVDocnet(num_filter=32, kernel_size=5)
    ckpt = torch.load(Path(repo) / "model" / "best_model.pkl", map_location="cpu", weights_only=True)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    class Grid2D(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, img):
            return self.m(img)[0]

    dummy = torch.rand(1, 3, 712, 488)
    torch.onnx.export(
        Grid2D(model), (dummy,), out,
        input_names=["img"], output_names=["grid"], opset_version=17, dynamo=False,
    )
    with torch.no_grad():
        ref = Grid2D(model)(dummy)
    import onnxruntime as ort
    got = ort.InferenceSession(out).run(None, {"img": dummy.numpy()})[0]
    print("onnx vs torch max abs diff:", float(abs(got - ref.numpy()).max()), "shape", got.shape)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

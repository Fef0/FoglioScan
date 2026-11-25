"""Export UVDoc (MIT, SIGGRAPH Asia 2023) to ONNX, for conversion to LiteRT.

Usage:
    git clone https://github.com/tanguymagne/UVDoc
    python tools/models/export_uvdoc.py UVDoc uvdoc.onnx [photo.jpg ...]

Photos given are used to check that ONNX Runtime matches PyTorch on real pages.

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


def main(repo: str, out: str, photos: list[str]) -> None:
    sys.path.insert(0, str(Path(repo).resolve()))
    from model import UVDocnet  # noqa: E402  (plain torch.nn modules, see model.py)

    model = UVDocnet(num_filter=32, kernel_size=5)
    ckpt = torch.load(Path(repo) / "model" / "best_model.pkl", map_location="cpu", weights_only=True)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    class GatherReflectConv(torch.nn.Module):
        """A reflect-padded conv with the padding written as index lookups (ONNX Gather).

        Same result as ``padding_mode="reflect"``, whose ONNX Pad(reflect) onnx2tf maps to the
        wrong axes.
        """

        def __init__(self, conv: torch.nn.Conv2d):
            super().__init__()
            self.pad = conv.padding[0]
            self.conv = torch.nn.Conv2d(
                conv.in_channels, conv.out_channels, conv.kernel_size, conv.stride,
                padding=0, dilation=conv.dilation, bias=conv.bias is not None,
            )
            self.conv.load_state_dict(conv.state_dict())

        @staticmethod
        def reflect(n: int, p: int) -> torch.Tensor:
            return torch.tensor(list(range(p, 0, -1)) + list(range(n)) + list(range(n - 2, n - 2 - p, -1)))

        def forward(self, x):
            x = x.index_select(2, self.reflect(x.shape[2], self.pad))
            x = x.index_select(3, self.reflect(x.shape[3], self.pad))
            return self.conv(x)

    import copy
    original = copy.deepcopy(model).eval()
    head = model.out_point_positions2D
    for i, layer in enumerate(head):
        if isinstance(layer, torch.nn.Conv2d) and layer.padding_mode == "reflect":
            head[i] = GatherReflectConv(layer)

    class Grid2D(torch.nn.Module):
        """The 2D grid branch only (the 3D head is a training aid)."""

        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, img):
            m = self.m
            x = m.resnet_down(m.resnet_head(img))
            bridges = [getattr(m, f"bridge_{i}")(x) for i in range(1, 7)]
            return m.out_point_positions2D(m.bridge_concat(torch.cat(bridges, dim=1)))

    dummy = torch.rand(1, 3, 712, 488)
    # eval() on the wrapper too: export restores the wrapper's training flag on the whole tree.
    torch.onnx.export(
        Grid2D(model).eval(), (dummy,), out,
        input_names=["img"], output_names=["grid"], opset_version=17, dynamo=False,
    )
    model.eval()
    import numpy as np
    import onnxruntime as ort
    session = ort.InferenceSession(out)
    inputs = {"noise": dummy}
    for photo in photos:
        from PIL import Image
        rgb = Image.open(photo).convert("RGB").resize((488, 712), Image.BILINEAR)
        inputs[Path(photo).name] = torch.from_numpy(np.asarray(rgb, dtype=np.float32) / 255).permute(2, 0, 1)[None].contiguous()
    for name, x in inputs.items():
        with torch.no_grad():
            ref = original(x)[0].numpy()
        got = session.run(None, {"img": x.numpy()})[0]
        d = abs(got - ref)
        print(f"{name}: onnx vs torch max {d.max():.2e} mean {d.mean():.2e}, grid range [{ref.min():.2f}, {ref.max():.2f}]")



if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])

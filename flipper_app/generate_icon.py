#!/usr/bin/env python3
"""
Generate LaundR Flipper Zero icon (10x10 pixels, 1-bit black and white)
"""
from PIL import Image
import numpy as np

# Icon pattern (1 = black, 0 = white)
# Design: Washing machine with "$" symbol
icon = [
    [0,0,0,0,0,0,0,0,0,0],
    [0,1,1,1,1,1,1,1,1,0],
    [0,1,0,0,1,1,0,0,1,0],
    [0,1,0,1,1,1,1,0,1,0],
    [0,1,0,0,1,1,0,0,1,0],
    [0,1,0,1,1,1,1,0,1,0],
    [0,1,0,0,1,1,0,0,1,0],
    [0,1,0,0,0,0,0,0,1,0],
    [0,1,1,1,1,1,1,1,1,0],
    [0,0,0,0,0,0,0,0,0,0],
]

# Create image
img_array = np.array(icon, dtype=np.uint8) * 255
img = Image.fromarray(img_array, mode='L')
img = img.convert('1')  # 1-bit black and white

# Save
output_path = 'LaundR_flipper.png'
img.save(output_path)

print(f"✓ Generated {output_path}")
print(f"  Size: {img.size}")
print(f"  Mode: {img.mode}")
print(f"  Format: 1-bit black and white")

# Also generate a larger preview for viewing
preview = img.resize((100, 100), Image.NEAREST)
preview.save('LaundR_flipper_preview.png')
print(f"✓ Generated preview: LaundR_flipper_preview.png (100x100)")

# Show ASCII preview
print("\nASCII Preview:")
for row in icon:
    print(''.join(['█' if pixel else ' ' for pixel in row]))

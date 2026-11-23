import os
import zipfile

src_dir = r"C:\Users\Administrator\Documents\code\frameboost-v2\module-src"
out_zip = r"C:\Users\Administrator\Documents\code\frameboost-v2\FrameBoost-v2.0.0-A50.zip"

if os.path.exists(out_zip):
    os.remove(out_zip)

with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(src_dir):
        for file in files:
            full_path = os.path.join(root, file)
            # Relative path with POSIX forward slash
            rel_path = os.path.relpath(full_path, src_dir).replace('\\', '/')
            z.write(full_path, rel_path)
            print(f"Added: {rel_path}")

print(f"\nDone! Output: {out_zip} ({os.path.getsize(out_zip)} bytes)")

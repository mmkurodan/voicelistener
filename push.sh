#!/bin/bash
set -e

git add .
git commit -m "update" || true
git push

echo "=== Changes pushed and GitHub Actions triggered ==="

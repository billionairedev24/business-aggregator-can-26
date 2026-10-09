#!/usr/bin/env python3
"""Regenerate deploy/helm/schemas from the External Secrets Operator CRDs of the version Argo CD installs.

make helm-crd-schemas. The chart's SecretStore / ExternalSecret are validated against these committed schemas (by
deploy/helm/validate.sh, before the community CRD catalog), so the check follows the operator version we run and
does not change when the catalog's main branch does: in October 2026 the catalog's SecretStore schema stopped
compiling in kubeconform and every chart check failed with "could not find schema for SecretStore".

The version comes from deploy/argocd/app-of-apps/values.yaml (addons.external-secrets.targetRevision); ESO_VERSION
overrides it. Writes <group>/<kind>_<version>.json for each served version of the kinds the chart renders.
"""
import json
import os
import re
import sys
import urllib.request

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(HERE, "schemas")
KINDS = {"SecretStore", "ClusterSecretStore", "ExternalSecret"}


def eso_version():
    if os.environ.get("ESO_VERSION"):
        return os.environ["ESO_VERSION"].lstrip("v")
    values = yaml.safe_load(open(os.path.join(ROOT, "deploy", "argocd", "app-of-apps", "values.yaml")))
    return str(values["addons"]["external-secrets"]["targetRevision"]).lstrip("v")


def main():
    version = eso_version()
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        sys.exit(f"crd-schemas: unexpected External Secrets version {version!r}")
    url = f"https://raw.githubusercontent.com/external-secrets/external-secrets/v{version}/deploy/crds/bundle.yaml"
    with urllib.request.urlopen(url, timeout=60) as r:
        bundle = r.read().decode()
    written = []
    for doc in yaml.safe_load_all(bundle):
        if not doc or doc.get("kind") != "CustomResourceDefinition":
            continue
        kind = doc["spec"]["names"]["kind"]
        if kind not in KINDS:
            continue
        group = doc["spec"]["group"]
        for v in doc["spec"]["versions"]:
            schema = (v.get("schema") or {}).get("openAPIV3Schema")
            if not v.get("served") or not schema:
                continue
            os.makedirs(os.path.join(OUT, group), exist_ok=True)
            path = os.path.join(OUT, group, f"{kind.lower()}_{v['name']}.json")
            with open(path, "w") as f:
                json.dump(schema, f, indent=1, sort_keys=True)
                f.write("\n")
            written.append(os.path.relpath(path, ROOT))
    with open(os.path.join(OUT, "VERSION"), "w") as f:
        f.write(f"external-secrets {version}\n")
    print(f"crd-schemas: External Secrets {version}: " + ", ".join(sorted(written)))


if __name__ == "__main__":
    main()

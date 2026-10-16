# Extract the SHA-256 certificate fingerprint from apksigner output.
# Android Build Tools have emitted both:
#   Signer #1 certificate SHA-256 digest: ...
#   V2 Signer: certificate SHA-256 digest: ...
extract_apksigner_certificate_sha256() {
  awk -F': ' '
    /certificate SHA-256 digest:/ {
      digest = $NF
      gsub(/:/, "", digest)
      gsub(/[[:space:]]/, "", digest)
      print toupper(digest)
      exit
    }
  '
}

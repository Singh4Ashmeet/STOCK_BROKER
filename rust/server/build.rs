fn main() -> Result<(), Box<dyn std::error::Error>> {
    let protoc = protoc_bin_vendored::protoc_bin_path()?;
    // Use a known compiler for local development and CI, independent of PATH.
    unsafe { std::env::set_var("PROTOC", protoc) };

    let manifest_dir = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto_path = manifest_dir
        .join("..")
        .join("..")
        .join("proto")
        .join("stockbroker")
        .join("market")
        .join("v1")
        .join("market.proto");
    let include_dir = manifest_dir.join("..").join("..").join("proto");

    tonic_build::configure()
        .build_server(true)
        .build_client(true) // for integration tests
        .compile_protos(&[proto_path], &[include_dir])?;

    Ok(())
}

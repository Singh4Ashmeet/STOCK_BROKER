fn main() -> Result<(), Box<dyn std::error::Error>> {
    let manifest_dir = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto_path = manifest_dir.join("..").join("..").join("proto").join("stockbroker").join("market").join("v1").join("market.proto");
    let include_dir = manifest_dir.join("..").join("..").join("proto");

    tonic_build::configure()
        .build_server(true)
        .build_client(true) // for integration tests
        .compile_protos(&[proto_path], &[include_dir])?;

    Ok(())
}

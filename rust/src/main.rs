use std::env;
use std::process::Command;

fn main() {
    println!("[Rust] Starting VPN Core Manager & Routing Injector...");

    // دریافت مسیر دایرکتوری اختصاصی اپلیکیشن از کاتلین برای رفع خطای سندباکس[span_2](start_span)[span_2](end_span)
    let files_dir = env::var("APP_FILES_DIR").unwrap_or_else(|_| ".".to_string());
    
    let binary_path = format!("{}/sing-box", files_dir);
    let config_path = format!("{}/config.json", files_dir);

    println!("[Rust] Launching sing-box core with config at: {}", config_path);

    let status = Command::new(&binary_path)
        .arg("run")
        .arg("-c")
        .arg(&config_path)
        .status();

    match status {
        Ok(s) => println!("[Rust] sing-box exited with status: {}", s),
        Err(e) => eprintln!("[Rust] Failed to start sing-box binary: {}", e),
    }
}

use std::fs;
use std::process::Command;

fn main() {
        println!("[Rust] Starting VPN Core Manager & Routing Injector...");

            let config_content = r#"{
                      "log": {
                                "level": "info"
                      },
                            "dns": {
                                        "servers": [
                                                      "8.8.8.8",
                                                                "1.1.1.1"
                                        ]
                            },
                                  "inbounds": [
                                            {
                                                          "type": "tun",
                                                                    "tag": "tun-in",
                                                                              "interface_name": "tun0",
                                                                                        "inet4_address": "172.19.0.1/30",
                                                                                                  "auto_route": true,
                                                                                                            "strict_route": true
                                            }
                                  ],
                                        "outbounds": [
                                                    {
                                                                  "type": "direct",
                                                                            "tag": "direct"
                                                    }
                                        ]
            }"#;

                if let Err(e) = fs::write("config.json", config_content) {
                            eprintln!("[Rust] Error writing config.json: {}", e);
                                    return;
                }
                    println!("[Rust] config.json generated successfully.");

                        let binary_path = "./sing-box";
                            println!("[Rust] Launching sing-box core...");

                                let status = Command::new(binary_path)
                                        .arg("run")
                                                .arg("-c")
                                                        .arg("config.json")
                                                                .status();

                                                                    match status {
                                                                                Ok(s) => println!("[Rust] sing-box exited with status: {}", s),
                                                                                        Err(e) => eprintln!("[Rust] Failed to start sing-box binary: {}",e),
}
 }
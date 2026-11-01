use std::path::PathBuf;

fn default_config() -> Result<PathBuf, String> {
    #[cfg(target_os = "linux")]
    {
        let absolute=|name| std::env::var_os(name).filter(|v| !v.is_empty()).map(PathBuf::from).filter(|v| v.is_absolute());
        let base=absolute("XDG_CONFIG_HOME").or_else(||absolute("HOME").map(|v|v.join(".config")))
            .ok_or("HOME or an absolute XDG_CONFIG_HOME is required")?;
        Ok(base.join("sunshine"))
    }
    #[cfg(not(target_os = "linux"))]
    { Ok(PathBuf::from(r"C:\Program Files\Sunshine\config")) }
}

pub struct Options {
    pub config: PathBuf,
    pub listen: String,
    pub unified: bool,
    pub sunshine: Option<PathBuf>,
}

pub fn parse(args: &[String]) -> Result<Options, String> {
    let mut options = Options {
        config: PathBuf::new(),
        listen: "0.0.0.0:47995".into(),
        unified: false,
        sunshine: None,
    };
    let mut legacy = false;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--service" => {
                legacy = true;
                i += 1;
            }
            "--sunshine-service" => {
                options.unified = true;
                i += 1;
            }
            flag @ ("--sunshine-config" | "--listen" | "--sunshine-exe") => {
                let value = args
                    .get(i + 1)
                    .filter(|v| !v.is_empty())
                    .ok_or("Expected option value")?;
                match flag {
                    "--sunshine-config" => options.config = PathBuf::from(value),
                    "--listen" => options.listen = value.clone(),
                    _ => options.sunshine = Some(PathBuf::from(value)),
                };
                i += 2;
            }
            _ => return Err("Unknown service option".into()),
        }
    }
    if legacy && options.unified {
        return Err("Choose a single service mode".into());
    }
    if options.unified && options.sunshine.is_none() {
        return Err("Unified service requires --sunshine-exe".into());
    }
    if !options.unified && options.sunshine.is_some() {
        return Err("--sunshine-exe requires --sunshine-service".into());
    }
    if options.config.as_os_str().is_empty(){options.config=default_config()?;}
    Ok(options)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn args(text: &[&str]) -> Vec<String> {
        text.iter().map(|v| (*v).into()).collect()
    }
    #[test]
    fn unified_service_has_one_explicit_engine_and_keeps_existing_config() {
        let options = parse(&args(&[
            "--sunshine-service",
            "--sunshine-exe",
            r"C:\Program Files\Sunshine\sunshine.exe",
            "--sunshine-config",
            r"D:\Existing\config",
        ]))
        .unwrap();
        assert!(options.unified);
        assert_eq!(options.config, PathBuf::from(r"D:\Existing\config"));
        assert_eq!(options.listen, "0.0.0.0:47995");
    }
    #[test]
    fn conflicting_modes_and_incomplete_engine_arguments_are_rejected() {
        for values in [
            &["--service", "--sunshine-service"][..],
            &["--sunshine-service"][..],
            &["--sunshine-exe", "x"][..],
            &["--listen"][..],
        ] {
            assert!(parse(&args(values)).is_err());
        }
        assert!(parse(&args(&["--service"])).is_ok());
    }
}

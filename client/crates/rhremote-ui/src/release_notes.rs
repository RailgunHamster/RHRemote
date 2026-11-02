//! CommonMark blocks rendered by native Slint widgets, not a web surface.
use pulldown_cmark::{Event, Parser, Tag, TagEnd};
use slint::{ModelRc, VecModel};

#[derive(Default)]
struct Block {
    content: String,
    plain: String,
    level: i32,
    prefix: String,
    indent: f32,
    code: bool,
    quote: bool,
    rule: bool,
}
fn escaped(text: &str) -> String {
    let mut out = String::new();
    for c in text.chars() {
        if "\\`*_{}[]<>~".contains(c) {
            out.push('\\');
        }
        out.push(c);
    }
    out
}
fn blocks(source: &str) -> Vec<Block> {
    let mut output = vec![];
    let mut current = Block::default();
    let mut lists: Vec<Option<u64>> = vec![];
    let mut links: Vec<Option<String>> = vec![];
    let mut quote = 0;
    let flush = |b: &mut Block, out: &mut Vec<Block>| {
        if !b.content.trim().is_empty() || b.rule {
            out.push(std::mem::take(b));
        }
    };
    let source = &source[..source.floor_char_boundary(source.len().min(65536))];
    for event in Parser::new(source) {
        if output.len() >= 512 {
            break;
        }
        match event {
            Event::Start(Tag::Heading { level, .. }) => {
                flush(&mut current, &mut output);
                current.level = level as i32;
            }
            Event::End(TagEnd::Heading(_) | TagEnd::Paragraph) => flush(&mut current, &mut output),
            Event::Start(Tag::Paragraph) => {
                current.quote = quote > 0;
                current.indent = lists.len().saturating_sub(1).min(4) as f32 * 16.;
            }
            Event::Start(Tag::List(start)) => {
                flush(&mut current, &mut output);
                lists.push(start);
            }
            Event::End(TagEnd::List(_)) => {
                flush(&mut current, &mut output);
                lists.pop();
            }
            Event::Start(Tag::Item) => {
                flush(&mut current, &mut output);
                current.prefix = match lists.last_mut() {
                    Some(Some(number)) => {
                        let p = format!("{number}.");
                        *number += 1;
                        p
                    }
                    _ => "•".into(),
                };
                current.indent = lists.len().saturating_sub(1).min(4) as f32 * 16.;
                current.quote = quote > 0;
            }
            Event::End(TagEnd::Item) => flush(&mut current, &mut output),
            Event::Start(Tag::BlockQuote(_)) => {
                flush(&mut current, &mut output);
                quote += 1;
                current.quote = true;
            }
            Event::End(TagEnd::BlockQuote(_)) => {
                flush(&mut current, &mut output);
                quote -= 1;
            }
            Event::Start(Tag::CodeBlock(_)) => {
                flush(&mut current, &mut output);
                current.code = true;
            }
            Event::End(TagEnd::CodeBlock) => flush(&mut current, &mut output),
            Event::Text(t) => {
                current.plain.push_str(&t);
                if current.code {
                    current.content.push_str(&t);
                } else {
                    current.content.push_str(&escaped(&t));
                }
            }
            Event::Code(t) => {
                current.plain.push_str(&t);
                let ticks = "`".repeat(t.split(|c| c != '`').map(str::len).max().unwrap_or(0) + 1);
                current.content.push_str(&format!("{ticks} {t} {ticks}"));
            }
            Event::Start(Tag::Link { dest_url, .. }) => {
                let url = link_url(&dest_url).ok();
                if url.is_some() {
                    current.content.push('[');
                }
                links.push(url);
            }
            Event::End(TagEnd::Link) => {
                if let Some(Some(url)) = links.pop() {
                    current.content.push_str(&format!("](<{url}>)"));
                }
            }
            Event::Html(t) | Event::InlineHtml(t) => {
                current.plain.push_str(&t);
                current.content.push_str(&escaped(&t));
            }
            Event::Start(Tag::Strong) | Event::End(TagEnd::Strong) => {
                current.content.push_str("**")
            }
            Event::Start(Tag::Emphasis) | Event::End(TagEnd::Emphasis) => current.content.push('*'),
            Event::SoftBreak | Event::HardBreak => {
                current.content.push('\n');
                current.plain.push('\n');
            }
            Event::Rule => {
                flush(&mut current, &mut output);
                current.rule = true;
                flush(&mut current, &mut output);
            }
            _ => {}
        }
    }
    flush(&mut current, &mut output);
    output
}
pub(crate) fn model(source: &str) -> ModelRc<crate::NoteBlock> {
    ModelRc::new(VecModel::from(
        blocks(source)
            .into_iter()
            .map(|b| crate::NoteBlock {
                text: if b.code {
                    slint::StyledText::from_plain_text(&b.content)
                } else {
                    slint::StyledText::from_markdown(&if b.level > 0 {
                        format!("<b>{}</b>", b.content)
                    } else {
                        b.content.clone()
                    })
                    .unwrap_or_else(|_| slint::StyledText::from_plain_text(&b.plain))
                },
                level: b.level,
                prefix: b.prefix.into(),
                indent: b.indent,
                code: b.code,
                quote: b.quote,
                rule: b.rule,
            })
            .collect::<Vec<_>>(),
    ))
}
pub(crate) fn history(
    items: &serde_json::Value,
    expanded: &serde_json::Value,
    exclude: &str,
) -> ModelRc<crate::ReleaseHistoryRow> {
    ModelRc::new(VecModel::from(
        items
            .as_array()
            .into_iter()
            .flatten()
            .filter(|r| r["version"] != exclude)
            .map(|r| {
                let version = r["version"].as_str().unwrap_or("");
                let open = expanded
                    .as_array()
                    .is_some_and(|a| a.iter().any(|v| v == version));
                crate::ReleaseHistoryRow {
                    version: version.into(),
                    published: r["publishedAt"]
                        .as_str()
                        .unwrap_or("")
                        .replace('T', " ")
                        .replace('Z', " UTC")
                        .into(),
                    expanded: open,
                    blocks: if open {
                        model(r["notes"].as_str().unwrap_or(""))
                    } else {
                        ModelRc::default()
                    },
                }
            })
            .collect::<Vec<_>>(),
    ))
}
fn link_url(address: &str) -> Result<String, String> {
    let url = url::Url::parse(address).map_err(|_| "链接地址无效")?;
    if !matches!(url.scheme(), "http" | "https")
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("只支持 HTTP / HTTPS 链接".into());
    }
    Ok(url.to_string())
}
pub(crate) fn open_link(address: &str) -> Result<serde_json::Value, String> {
    let address = link_url(address)?;
    #[cfg(windows)]
    unsafe {
        use windows::{
            Win32::{UI::Shell::ShellExecuteW, UI::WindowsAndMessaging::SW_SHOWNORMAL},
            core::{PCWSTR, w},
        };
        let wide: Vec<u16> = address.encode_utf16().chain(Some(0)).collect();
        let result = ShellExecuteW(
            None,
            w!("open"),
            PCWSTR(wide.as_ptr()),
            None,
            None,
            SW_SHOWNORMAL,
        );
        if result.0 as usize <= 32 {
            return Err("无法打开浏览器".into());
        }
    }
    #[cfg(not(windows))]
    {
        std::process::Command::new("xdg-open")
            .arg(&address)
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .map_err(|_| "无法打开浏览器")?;
    }
    Ok(serde_json::json!({"opened":true,"url":address}))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn markdown_separates_headings_lists_quotes_and_code_without_raw_markers() {
        let b = blocks(
            "# 版本\n\n## 修复\n\n- **清晰**与 `代码`\n- 第二项\n\n> 提示\n\n```txt\na < b\n```\n",
        );
        assert_eq!(b[0].level, 1);
        assert_eq!(b[1].level, 2);
        assert_eq!(b[2].prefix, "•");
        assert_eq!(b[2].plain, "清晰与 代码");
        assert!(b.iter().any(|p| p.quote));
        assert!(b.iter().any(|p| p.code && p.plain.contains("a < b")));
    }
    #[test]
    fn escaped_text_and_inline_style_generate_valid_styled_text() {
        for b in
            blocks("1. **粗体** *强调*\n2. `a*b` [说明](https://example.com)\n\n<script>x</script>")
        {
            if !b.code {
                assert!(
                    slint::StyledText::from_markdown(&b.content).is_ok(),
                    "{}",
                    b.content
                );
            }
        }
    }
}

from harness import prompts


def test_versioned_templates_exist():
    template_ids = {template_id for template_id, _ in prompts.list_templates()}
    assert {"generate_tests", "review_comments"} <= template_ids


def test_templates_contain_diff_placeholder():
    for template_id, version in prompts.list_templates():
        content = prompts.load_template(template_id, version)
        assert "{{diff}}" in content

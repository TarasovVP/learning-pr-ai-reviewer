#!/usr/bin/env ruby
require "pathname"
require "yaml"

ROOT = Pathname.new(__dir__).parent
ACTIVITIES = ROOT.join(".ai-review", "activities")
SAFE_ID = /\A[a-z0-9][a-z0-9-]*\z/
SEMVER = /\A\d+\.\d+\.\d+\z/
ASSESSMENT_KEYS = %w[id version title description relatedPaths requirements].freeze
REQUIREMENT_KEYS = %w[id title criteria acceptableEvidence statusRules].freeze

def fail_catalog(message)
  warn "Catalog validation failed: #{message}"
  exit 1
end

def regular_file!(path)
  fail_catalog("#{path} must be a regular non-symlink file") unless path.file? && !path.symlink?
end

def text?(value)
  value.is_a?(String) && !value.strip.empty?
end

def text_array?(value)
  value.is_a?(Array) && !value.empty? && value.all? { |item| text?(item) }
end

def validate_assessment(path, expected_id)
  regular_file!(path)
  data = YAML.safe_load(path.read, permitted_classes: [], permitted_symbols: [], aliases: false)
  fail_catalog("#{path} must contain a mapping") unless data.is_a?(Hash)
  fail_catalog("#{path} has unexpected keys") unless data.keys.sort == ASSESSMENT_KEYS.sort
  fail_catalog("#{path} id must equal #{expected_id}") unless data["id"] == expected_id
  fail_catalog("#{path} has an invalid semantic version") unless data["version"].is_a?(String) && SEMVER.match?(data["version"])
  %w[title description].each { |key| fail_catalog("#{path} #{key} is empty") unless text?(data[key]) }
  fail_catalog("#{path} relatedPaths must be a unique string array") unless data["relatedPaths"].is_a?(Array) && data["relatedPaths"].all? { |item| text?(item) } && data["relatedPaths"].uniq == data["relatedPaths"]
  requirements = data["requirements"]
  fail_catalog("#{path} requires at least one requirement") unless requirements.is_a?(Array) && !requirements.empty?
  identifiers = []
  requirements.each_with_index do |requirement, index|
    prefix = "#{path} requirement #{index + 1}"
    fail_catalog("#{prefix} must be a mapping") unless requirement.is_a?(Hash)
    fail_catalog("#{prefix} has unexpected keys") unless requirement.keys.sort == REQUIREMENT_KEYS.sort
    identifier = requirement["id"]
    fail_catalog("#{prefix} has invalid id") unless identifier.is_a?(String) && SAFE_ID.match?(identifier)
    identifiers << identifier
    fail_catalog("#{prefix} title is empty") unless text?(requirement["title"])
    fail_catalog("#{prefix} criteria is invalid") unless text_array?(requirement["criteria"])
    fail_catalog("#{prefix} acceptableEvidence is invalid") unless text_array?(requirement["acceptableEvidence"])
    rules = requirement["statusRules"]
    fail_catalog("#{prefix} statusRules is invalid") unless rules.is_a?(Hash) && !rules.empty? && rules.all? { |key, value| text?(key) && text?(value) }
  end
  fail_catalog("#{path} contains duplicate requirement ids") unless identifiers.uniq == identifiers
end

fail_catalog("#{ACTIVITIES} is missing") unless ACTIVITIES.directory? && !ACTIVITIES.symlink?
activity_count = 0
assessment_count = 0
ACTIVITIES.children.sort.each do |activity|
  next unless activity.directory?
  fail_catalog("#{activity} must not be a symlink") if activity.symlink?
  activity_id = activity.basename.to_s
  fail_catalog("invalid activity id #{activity_id}") unless SAFE_ID.match?(activity_id)
  assessments = activity.join("assessments")
  assignments = activity.join("assignments")
  fail_catalog("#{activity} needs an assessments directory") unless assessments.directory? && !assessments.symlink?
  seen = {}
  files = assessments.children.select { |path| path.file? && %w[.yaml .yml].include?(path.extname.downcase) }
  fail_catalog("#{activity} has no assessments") if files.empty?
  files.sort.each do |assessment|
    assessment_id = assessment.basename(assessment.extname).to_s
    fail_catalog("invalid assessment id #{assessment_id}") unless SAFE_ID.match?(assessment_id)
    fail_catalog("duplicate assessment id #{assessment_id}") if seen[assessment_id]
    seen[assessment_id] = true
    validate_assessment(assessment, assessment_id)
    assessment_count += 1
  end
  if assignments.exist?
    fail_catalog("#{assignments} must be a regular directory") unless assignments.directory? && !assignments.symlink?
    assignments.children.sort.each do |assignment|
      regular_file!(assignment)
      fail_catalog("#{assignment} must use .md") unless assignment.extname.downcase == ".md"
      fail_catalog("#{assignment} is empty") unless text?(assignment.read)
      assignment_id = assignment.basename(".md").to_s
      fail_catalog("#{assignment} has no matching assessment") unless seen[assignment_id]
    end
  end
  activity_count += 1
end
fail_catalog("catalog has no learning activities") if activity_count.zero?
puts "Validated #{assessment_count} assessment(s) across #{activity_count} learning activity catalog(s)."

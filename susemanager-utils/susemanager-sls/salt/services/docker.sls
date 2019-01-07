{% if pillar['addon_group_types'] is defined and 'container_build_host' in pillar['addon_group_types'] %}
mgr_install_docker:
  pkg.installed:
    - pkgs:
      {% if grains['os_family'] == 'Suse' %}
      - docker: '>=1.9.0'
      {% elif grains['os_family'] == 'Debian' %}
      - docker.io: '>=1.9.0'
      {% endif %}
{%- if grains['pythonversion'][0] == 3 %}
  {%- if grains['os_family'] == 'Suse' %}
    {%- if grains['osmajorrelease'] == 12 %}
      - python3-docker-py: '>=1.6.0'
    {%- else %}
      - python3-docker: '>=1.6.0'
    {%- endif %}
  {%- elif grains['os_family'] == 'Debian' -%}
    - python3-docker: '>=1.6.0'
  {%- endif %}
{%- else %}
  {%- if grains['os_family'] == 'Suse' %}
      - python-docker-py: '>=1.6.0'
  {%- elif grains['os_family'] == 'Debian' -%}
    - python-docker: '>=1.6.0'
  {%- endif %}
{%- endif %}
{%- if grains['os_family'] == 'Suse' %}
  {%- if grains['saltversioninfo'][0] >= 2018 %}
      {%- if grains['osmajorrelease'] == 12 %}
        - python3-salt
      {%- else %}
        - python2-salt
      {%- endif %}
  {%- endif %}
{%- endif %}

mgr_docker_service:
  service.running:
    - name: docker
    - enable: True
    - require:
      - pkg: mgr_install_docker

mgr_min_salt:
  pkg.installed:
    - pkgs:
      - salt: '>=2016.11.1'
      - salt-minion: '>=2016.11.1'
    - order: last
{% endif %}
